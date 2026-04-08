package com.travel.travel_system.service.impl;

import com.travel.travel_system.algorithm.model.RoadRestriction;
import com.travel.travel_system.dto.MapMatchingResult;
import com.travel.travel_system.dto.Projection;
import com.travel.travel_system.dto.TripRouteSnapshotPayload;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.service.TrackPointService;
import com.travel.travel_system.service.pub.RedisService;
import com.travel.travel_system.algorithm.model.*;
import com.travel.travel_system.algorithm.model.RoadGraph;
import com.travel.travel_system.algorithm.RoadGraphService;
import com.travel.travel_system.algorithm.model.RoadSegment;
import com.travel.travel_system.vo.GeoPointVO;
import com.travel.travel_system.vo.TrackPolylineVO;
import com.travel.travel_system.vo.enums.CoordTypeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Supplier;

@Service
public class TrackPointServiceImpl implements TrackPointService {

    private static final Logger log = LoggerFactory.getLogger(TrackPointServiceImpl.class);
    private static final int LOG_CANDIDATE_LIMIT = 6;
    private static final int LOG_ROUTE_PREVIEW_LIMIT = 80;

    private static final int DEBUG_TARGET_WINDOW = 0;
    private static final int DEBUG_POINT_FROM = 15;
    private static final int DEBUG_POINT_TO = 25;
    private static final boolean DEBUG_ONLY_ABNORMAL = false;
    private static final int DEBUG_CANDIDATE_TOP_K = 2;
    private static final Set<Long> DEBUG_TARGET_WAY_IDS = new LinkedHashSet<>(Arrays.asList(
            1308329376L,
            1308329377L,
            1308329378L,
            1308329381L
    ));

    /**
     * 论文参数/工程默认值：
     * - 复杂度邻域半径默认 40m（论文在实验中选定）；
     * - 简单段：方向约束的一阶 HMM；
     * - 复杂段：二阶 HMM + 路网复杂度自适应权重；
     * - 复杂点前后补 2 个普通点。
     */
    private static final double COMPLEXITY_RADIUS_METERS = 40.0;
    private static final double CANDIDATE_RADIUS_METERS = 40.0;
    private static final double CANDIDATE_FALLBACK_RADIUS_METERS = 80.0;
    private static final int MAX_CANDIDATES_PER_POINT = 6;
    private static final int COMPLEX_PADDING_POINTS = 2;

    private static final double SIGMA_DISTANCE_METERS = 15.0;
    private static final double SIGMA_HEADING_DEGREES = 25.0;
    private static final double BETA_TRANSITION_METERS = 20.0;
    private static final double LAMBDA_DIRECTION = 0.06;
    private static final double SECOND_ORDER_LAMBDA_METERS = 30.0;
    private static final double MIN_PROB = 1e-12;

    private static final int WINDOW_POINT_COUNT = 30;
    private static final int WINDOW_OVERLAP_POINT_COUNT = 8;
    private static final double WINDOW_CORE_BUFFER_METERS = 150.0;
    private static final double WINDOW_HALO_BUFFER_METERS = 350.0;
    private static final double TILE_SIZE_METERS = 1200.0;
    private static final double TILE_OVERLAP_METERS = 250.0;
    private static final double BOUNDARY_EXPAND_METERS = 800.0;

    private static final String CHINA_PBF = "static/pbf/china-260317.osm.pbf";
    private static final String HENAN_PBF = "static/pbf/henan-260321.osm.pbf";

    private static final String MATCH_LATEST_KEY_PREFIX = "track_match:latest:";
    private static final String MATCH_DIRTY_KEY_PREFIX = "track_match:dirty:";
    private static final String MATCH_FINGERPRINT_KEY_PREFIX = "track_match:fingerprint:";
    private static final String MATCH_LOCK_KEY_PREFIX = "track_match:lock:";
    private static final String MATCH_ALGO_VERSION = "paper-split-hmm-v3";
    private static final long MATCH_LATEST_TTL_SECONDS = 6 * 60 * 60L;
    private static final long MATCH_DIRTY_TTL_SECONDS = 24 * 60 * 60L;
    private static final long MATCH_LOCK_TTL_SECONDS = 5 * 60L;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private RedisService redisService;

    /**
     * 新路网读取体系直接使用重构后的 RoadGraphService，
     * 不再走老的 center+radius 读图入口。
     */
    private final RoadGraphService roadGraphService = new RoadGraphService();

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ThreadLocal<Object> matchingContext = new ThreadLocal<>();
    private final ThreadLocal<Integer> currentWindowIndex = new ThreadLocal<>();
    private final Map<String, Double> routeDistanceCache = Collections.synchronizedMap(new LinkedHashMap<String, Double>(20000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Double> eldest) {
            return size() > 20000;
        }
    });


    private final Map<String, LatestMatchPayload> latestMatchMemoryCache = Collections.synchronizedMap(new LinkedHashMap<String, LatestMatchPayload>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LatestMatchPayload> eldest) {
            return size() > 64;
        }
    });

    private <T> T withMatchingContext(Supplier<T> supplier) {
        matchingContext.set(new Object());
        try {
            return supplier.get();
        } finally {
            matchingContext.remove();
        }
    }

    @Override
    public void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return;
        }
        trackPointRepository.saveAll(trackPoints);
        markTripMatchDirty(tripId);
    }

    @Override
    public List<TrackPoint> getTrackPoints(Long tripId, long startTimestamp, long endTimestamp) {
        return trackPointRepository.findByTripIdAndTsBetween(tripId, startTimestamp, endTimestamp);
    }

    @Override
    public List<TrackPoint> smoothTrackPoints(List<TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.size() < 3) {
            return trackPoints == null ? new ArrayList<>() : trackPoints;
        }
        List<TrackPoint> sorted = sortByTimestamp(trackPoints);
        List<TrackPoint> smoothed = new ArrayList<>(sorted.size());

        for (int i = 0; i < sorted.size(); i++) {
            int from = Math.max(0, i - 2);
            int to = Math.min(sorted.size() - 1, i + 2);

            double latSum = 0.0;
            double lonSum = 0.0;
            double weightSum = 0.0;
            for (int j = from; j <= to; j++) {
                double weight = 1.0 / (1.0 + Math.abs(i - j));
                latSum += decodeDouble(sorted.get(j).getLatEnc()) * weight;
                lonSum += decodeDouble(sorted.get(j).getLngEnc()) * weight;
                weightSum += weight;
            }

            TrackPoint copy = copyTrackPoint(sorted.get(i));
            copy.setLatEnc(encodeDouble(latSum / Math.max(weightSum, 1.0)));
            copy.setLngEnc(encodeDouble(lonSum / Math.max(weightSum, 1.0)));
            smoothed.add(copy);
        }
        return smoothed;
    }

    @Override
    public List<TrackPoint> getTrackPointsByRange(Long tripId, long startTimestamp, long endTimestamp) {
        return trackPointRepository.findByTripIdAndTsBetween(tripId, startTimestamp, endTimestamp);
    }

    @Override
    public List<MapMatchingResult> matchTrajectory(Long tripId) {
        return withMatchingContext(() -> {
            List<TrackPoint> raw = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
            if (raw == null || raw.isEmpty()) {
                return new ArrayList<>();
            }
            return matchTrajectory(raw);
        });
    }

    public List<MapMatchingResult> matchTrajectory(List<TrackPoint> trackPoints) {
        return withMatchingContext(() -> {
            List<TrackPoint> prepared = smoothTrackPoints(normalizeTrackPointsToWgs84(trackPoints));
            if (prepared.isEmpty()) {
                return new ArrayList<>();
            }

            List<GeoCoord> trajectoryWgs84 = toGeoCoords(prepared);
            String resourcePath = resolveRoadResource(trajectoryWgs84);

            log.warn("[MAP_MATCH_START] pointCount={} resourcePath={} firstPoint=({}, {}) lastPoint=({}, {})",
                    prepared.size(), resourcePath,
                    round6(trajectoryWgs84.get(0).getLat()), round6(trajectoryWgs84.get(0).getLon()),
                    round6(trajectoryWgs84.get(trajectoryWgs84.size() - 1).getLat()), round6(trajectoryWgs84.get(trajectoryWgs84.size() - 1).getLon()));

            RoadGraphService.LoadContext loadContext = roadGraphService.prepare(trajectoryWgs84, resourcePath);
            log.warn("[MAP_MATCH_START] windows={}", loadContext.getWindows() == null ? 0 : loadContext.getWindows().size());
            List<MapMatchingResult> merged = new ArrayList<>();
            WindowSeed carrySeed = WindowSeed.empty();

            for (RoadGraphService.Window window : loadContext.getWindows()) {
                currentWindowIndex.set(window.getIndex());
                RoadGraph graph = roadGraphService.loadWindowGraph(loadContext, window.getIndex());
                logWindowGraph(window, graph);

                List<TrackPoint> windowPoints = prepared.subList(window.getStartIndex(), window.getEndIndex() + 1);
                List<CandidateSet> candidateSets = buildCandidateSets(windowPoints, graph);
                logCandidateSets(window, candidateSets);
                List<SegmentPlan> segmentPlans = buildSegmentPlans(candidateSets, graph);
                logSegmentPlans(window, segmentPlans);

                List<MapMatchingResult> windowResults = new ArrayList<>();
                WindowSeed rollingSeed = carrySeed;
                for (SegmentPlan plan : segmentPlans) {
                    List<MapMatchingResult> partial;
                    if (plan.complexSegment) {
                        partial = runSecondOrderHmmSegment(windowPoints, candidateSets, graph, plan.startInclusive, plan.endInclusive, window.getStartIndex(), rollingSeed);
                    } else {
                        partial = runFirstOrderHmmSegment(windowPoints, candidateSets, graph, plan.startInclusive, plan.endInclusive, window.getStartIndex(), rollingSeed);
                    }
                    appendDedup(windowResults, partial);
                    rollingSeed = updateSeedFromResults(rollingSeed, partial, candidateSets, plan.startInclusive, plan.endInclusive);
                }

                logChosenRoute("[MAP_MATCH_WINDOW_RESULT]", "window=" + window.getIndex() + " beforeBoundaryCheck", windowResults, graph);
                logConnectivitySummary("[MAP_MATCH_WINDOW_CONNECT]", windowResults, graph);

                if (touchesBoundary(windowResults, window)) {
                    RoadGraph expanded = roadGraphService.expandForward(loadContext, window.getIndex(), window.getHeadingDegrees());
                    List<CandidateSet> expandedCandidateSets = buildCandidateSets(windowPoints, expanded);
                    logCandidateSets(window, expandedCandidateSets);
                    List<SegmentPlan> expandedPlans = buildSegmentPlans(expandedCandidateSets, expanded);
                    logSegmentPlans(window, expandedPlans);

                    windowResults.clear();
                    rollingSeed = carrySeed;
                    for (SegmentPlan plan : expandedPlans) {
                        List<MapMatchingResult> partial;
                        if (plan.complexSegment) {
                            partial = runSecondOrderHmmSegment(windowPoints, expandedCandidateSets, expanded, plan.startInclusive, plan.endInclusive, window.getStartIndex(), rollingSeed);
                        } else {
                            partial = runFirstOrderHmmSegment(windowPoints, expandedCandidateSets, expanded, plan.startInclusive, plan.endInclusive, window.getStartIndex(), rollingSeed);
                        }
                        appendDedup(windowResults, partial);
                        rollingSeed = updateSeedFromResults(rollingSeed, partial, expandedCandidateSets, plan.startInclusive, plan.endInclusive);
                    }
                    logChosenRoute("[MAP_MATCH_WINDOW_EXPANDED_RESULT]", "window=" + window.getIndex(), windowResults, expanded);
                    logConnectivitySummary("[MAP_MATCH_WINDOW_EXPANDED_CONNECT]", windowResults, expanded);
                }

                appendWindowResultsWithOverlapDedup(merged, windowResults, loadContext, window);
                RoadGraphService.Window nextWindow = window.getIndex() + 1 < loadContext.getWindows().size()
                        ? loadContext.getWindows().get(window.getIndex() + 1)
                        : null;
                carrySeed = buildCarrySeedForNextWindow(windowResults, window, nextWindow);
            }

            currentWindowIndex.remove();
            resequence(merged);
            logChosenRoute("[MAP_MATCH_FINAL_ROUTE]", "global", merged, loadContext.getMergedGraph());
            logConnectivitySummary("[MAP_MATCH_FINAL_CONNECT]", merged, loadContext.getMergedGraph());
            return merged;
        });
    }


    @Async
    public void precomputeMatchCacheAsync(Long tripId) {
        try {
            recomputeTripMatchIfNeeded(tripId);
        } catch (Exception e) {
            log.warn("[TRACK_MATCH_RECOMPUTE_ASYNC] tripId={} failed: {}", tripId, e.getMessage(), e);
        }
    }

    /**
     * ===========================
     * 一阶 HMM（简单段）
     * 论文对应：
     * - 观测概率: P(oi|cj) * P(theta_i,j)
     * - 转移概率: P(dt) * P(theta_t)
     * ===========================
     */
    private List<MapMatchingResult> runFirstOrderHmmSegment(List<TrackPoint> windowPoints,
                                                            List<CandidateSet> candidateSets,
                                                            RoadGraph graph,
                                                            int startInclusive,
                                                            int endInclusive,
                                                            int globalWindowStart,
                                                            WindowSeed seed) {
        if (startInclusive > endInclusive) {
            return new ArrayList<>();
        }

        Map<Long, Double> prevScores = new HashMap<>();
        Map<Integer, Map<Long, Long>> backPointers = new HashMap<>();

        CandidateSet firstSet = candidateSets.get(startInclusive);
        if (firstSet.candidates.isEmpty()) {
            return projectRaw(windowPoints, graph, startInclusive, endInclusive, globalWindowStart);
        }

        for (Candidate candidate : firstSet.candidates) {
            double score = safeLog(firstOrderObservationProbability(candidate));
            if (seed != null && seed.lastSegmentId != null) {
                Candidate pseudoPrev = buildSeedCandidate(seed.lastSegmentId, graph, seed.lastMatchedLat, seed.lastMatchedLon);
                if (pseudoPrev != null) {
                    score += safeLog(firstOrderContinuityPrior(pseudoPrev, candidate, graph));
                }
            }
            prevScores.put(candidate.segment.getSegmentId(), score);
        }

        for (int t = startInclusive + 1; t <= endInclusive; t++) {
            CandidateSet currentSet = candidateSets.get(t);
            if (currentSet.candidates.isEmpty()) {
                return projectRaw(windowPoints, graph, startInclusive, endInclusive, globalWindowStart);
            }

            Map<Long, Double> currentScores = new HashMap<>();
            Map<Long, Long> currentBackPointer = new HashMap<>();

            for (Candidate curr : currentSet.candidates) {
                double bestScore = Double.NEGATIVE_INFINITY;
                long bestPrevSegmentId = -1L;

                for (Candidate prev : candidateSets.get(t - 1).candidates) {
                    Double prevScore = prevScores.get(prev.segment.getSegmentId());
                    if (prevScore == null) {
                        continue;
                    }

                    double transition = firstOrderTransitionProbability(candidateSets.get(t - 1).rawPoint, prev,
                            currentSet.rawPoint, curr, graph);
                    double score = prevScore
                            + safeLog(firstOrderObservationProbability(curr))
                            + safeLog(transition);

                    if (score > bestScore) {
                        bestScore = score;
                        bestPrevSegmentId = prev.segment.getSegmentId();
                    }
                }

                currentScores.put(curr.segment.getSegmentId(), bestScore);
                currentBackPointer.put(curr.segment.getSegmentId(), bestPrevSegmentId);
            }

            prevScores = currentScores;
            backPointers.put(t, currentBackPointer);
            logTopCandidateScores("[MAP_MATCH_FIRST_ORDER_STEP]", t, currentScores, currentBackPointer, currentSet, graph);
        }

        long bestLast = argMax(prevScores);
        Map<Integer, Long> bestPath = new HashMap<>();
        bestPath.put(endInclusive, bestLast);
        for (int t = endInclusive; t > startInclusive; t--) {
            Long prev = backPointers.getOrDefault(t, Collections.emptyMap()).get(bestPath.get(t));
            if (prev == null || prev < 0) {
                break;
            }
            bestPath.put(t - 1, prev);
        }

        List<MapMatchingResult> results = new ArrayList<>();
        for (int t = startInclusive; t <= endInclusive; t++) {
            Candidate matched = findCandidateBySegmentId(candidateSets.get(t), bestPath.get(t));
            results.add(toResult(candidateSets.get(t), matched, globalWindowStart + t));
        }
        logChosenRoute("[MAP_MATCH_FIRST_ORDER_CHOSEN]", "range=" + startInclusive + "-" + endInclusive, results, graph);
        return results;
    }

    /**
     * ===========================
     * 二阶 HMM（复杂段）
     * 论文对应：
     * - 二阶状态转移刻画连续三个轨迹点
     * - 使用路网复杂度 F 自适应调节观测/转移权重
     * - 不与一阶 HMM 聚合成一个黑盒
     * ===========================
     */
    private List<MapMatchingResult> runSecondOrderHmmSegment(List<TrackPoint> windowPoints,
                                                             List<CandidateSet> candidateSets,
                                                             RoadGraph graph,
                                                             int startInclusive,
                                                             int endInclusive,
                                                             int globalWindowStart,
                                                             WindowSeed seed) {
        if (endInclusive - startInclusive < 2) {
            return runFirstOrderHmmSegment(windowPoints, candidateSets, graph, startInclusive, endInclusive, globalWindowStart, seed);
        }

        CandidateSet firstSet = candidateSets.get(startInclusive);
        CandidateSet secondSet = candidateSets.get(startInclusive + 1);
        if (firstSet.candidates.isEmpty() || secondSet.candidates.isEmpty()) {
            return projectRaw(windowPoints, graph, startInclusive, endInclusive, globalWindowStart);
        }

        Map<Long, Double> startProb = secondOrderStartProbabilities(firstSet);

        Map<StateKey, Double> prevScores = new HashMap<>();
        Map<Integer, Map<StateKey, StateKey>> backPointers = new HashMap<>();

        for (Candidate c0 : firstSet.candidates) {
            for (Candidate c1 : secondSet.candidates) {
                double pairObservation = secondOrderPairObservation(firstSet.rawPoint, c0, secondSet.rawPoint, c1, graph);
                double score = safeLog(startProb.getOrDefault(c0.segment.getSegmentId(), MIN_PROB))
                        + safeLog(pairObservation);
                if (seed != null && seed.secondLastSegmentId != null && seed.lastSegmentId != null) {
                    Candidate pseudoPrevPrev = buildSeedCandidate(seed.secondLastSegmentId, graph, seed.secondLastMatchedLat, seed.secondLastMatchedLon);
                    Candidate pseudoPrev = buildSeedCandidate(seed.lastSegmentId, graph, seed.lastMatchedLat, seed.lastMatchedLon);
                    if (pseudoPrevPrev != null && pseudoPrev != null) {
                        double continuity = secondOrderTransitionProbability(
                                seed.toTrackPoint(false), pseudoPrevPrev,
                                seed.toTrackPoint(true), pseudoPrev,
                                firstSet.rawPoint, c0,
                                graph
                        );
                        score += safeLog(continuity);
                    }
                } else if (seed != null && seed.lastSegmentId != null) {
                    Candidate pseudoPrev = buildSeedCandidate(seed.lastSegmentId, graph, seed.lastMatchedLat, seed.lastMatchedLon);
                    if (pseudoPrev != null) {
                        score += safeLog(firstOrderContinuityPrior(pseudoPrev, c0, graph));
                    }
                }
                prevScores.put(new StateKey(c0.segment.getSegmentId(), c1.segment.getSegmentId()), score);
            }
        }

        for (int t = startInclusive + 2; t <= endInclusive; t++) {
            CandidateSet currentSet = candidateSets.get(t);
            CandidateSet prevSet = candidateSets.get(t - 1);
            CandidateSet prevPrevSet = candidateSets.get(t - 2);
            if (currentSet.candidates.isEmpty()) {
                return projectRaw(windowPoints, graph, startInclusive, endInclusive, globalWindowStart);
            }

            Map<StateKey, Double> currentScores = new HashMap<>();
            Map<StateKey, StateKey> currentBack = new HashMap<>();

            for (Candidate prev : prevSet.candidates) {
                for (Candidate curr : currentSet.candidates) {
                    StateKey currentState = new StateKey(prev.segment.getSegmentId(), curr.segment.getSegmentId());

                    double bestScore = Double.NEGATIVE_INFINITY;
                    StateKey bestPrevState = null;

                    for (Candidate prevPrev : prevPrevSet.candidates) {
                        StateKey prevState = new StateKey(prevPrev.segment.getSegmentId(), prev.segment.getSegmentId());
                        Double prevScore = prevScores.get(prevState);
                        if (prevScore == null) {
                            continue;
                        }

                        double pairObservation = secondOrderPairObservation(prevSet.rawPoint, prev, currentSet.rawPoint, curr, graph);
                        double secondTransition = secondOrderTransitionProbability(
                                prevPrevSet.rawPoint, prevPrev,
                                prevSet.rawPoint, prev,
                                currentSet.rawPoint, curr,
                                graph
                        );

                        double complexity = clamp01(currentSet.complexityScore);
                        double adaptive = adaptiveBlend(pairObservation, secondTransition, complexity);

                        double score = prevScore + safeLog(adaptive);
                        if (score > bestScore) {
                            bestScore = score;
                            bestPrevState = prevState;
                        }
                    }

                    currentScores.put(currentState, bestScore);
                    currentBack.put(currentState, bestPrevState);
                }
            }

            prevScores = currentScores;
            backPointers.put(t, currentBack);
            logTopStateScores("[MAP_MATCH_SECOND_ORDER_STEP]", t, currentScores, currentBack, graph);
        }

        StateKey bestFinalState = argMaxState(prevScores);
        if (bestFinalState == null) {
            return projectRaw(windowPoints, graph, startInclusive, endInclusive, globalWindowStart);
        }

        Map<Integer, Long> bestPath = new HashMap<>();
        bestPath.put(endInclusive, bestFinalState.currSegmentId);
        bestPath.put(endInclusive - 1, bestFinalState.prevSegmentId);

        StateKey cursor = bestFinalState;
        for (int t = endInclusive; t >= startInclusive + 2; t--) {
            StateKey prevState = backPointers.getOrDefault(t, Collections.emptyMap()).get(cursor);
            if (prevState == null) {
                break;
            }
            bestPath.put(t - 2, prevState.prevSegmentId);
            cursor = prevState;
        }

        List<MapMatchingResult> results = new ArrayList<>();
        for (int t = startInclusive; t <= endInclusive; t++) {
            Candidate matched = findCandidateBySegmentId(candidateSets.get(t), bestPath.get(t));
            results.add(toResult(candidateSets.get(t), matched, globalWindowStart + t));
        }
        logChosenRoute("[MAP_MATCH_SECOND_ORDER_CHOSEN]", "range=" + startInclusive + "-" + endInclusive, results, graph);
        return results;
    }

    /**
     * 轨迹复杂度与分段：
     * - F_i = max(F(a), F(c))
     * - 复杂点前后各补 2 个普通点
     */
    private List<SegmentPlan> buildSegmentPlans(List<CandidateSet> sets, RoadGraph graph) {
        List<SegmentPlan> plans = new ArrayList<>();
        if (sets == null || sets.isEmpty()) {
            return plans;
        }

        boolean[] complex = new boolean[sets.size()];
        for (int i = 0; i < sets.size(); i++) {
            complex[i] = isComplexPoint(sets.get(i), graph);
        }

        boolean[] expanded = new boolean[sets.size()];
        for (int i = 0; i < complex.length; i++) {
            if (!complex[i]) {
                continue;
            }
            int from = Math.max(0, i - COMPLEX_PADDING_POINTS);
            int to = Math.min(complex.length - 1, i + COMPLEX_PADDING_POINTS);
            for (int j = from; j <= to; j++) {
                expanded[j] = true;
            }
        }

        int start = 0;
        while (start < sets.size()) {
            boolean isComplex = expanded[start];
            int end = start;
            while (end + 1 < sets.size() && expanded[end + 1] == isComplex) {
                end++;
            }
            plans.add(new SegmentPlan(start, end, isComplex));
            start = end + 1;
        }
        return plans;
    }

    private boolean isComplexPoint(CandidateSet set, RoadGraph graph) {
        if (set == null || set.candidates.isEmpty()) {
            return false;
        }
        // 论文思想：方向复杂度 + 连通复杂度
        return set.maxNodeDegree >= 4
                || set.directionComplexity >= 0.5
                || set.complexityScore >= 0.5;
    }

    private List<CandidateSet> buildCandidateSets(List<TrackPoint> windowPoints, RoadGraph graph) {
        List<CandidateSet> sets = new ArrayList<>();
        for (int i = 0; i < windowPoints.size(); i++) {
            TrackPoint raw = windowPoints.get(i);
            double lat = decodeDouble(raw.getLatEnc());
            double lon = decodeDouble(raw.getLngEnc());

            List<RoadSegment> nearby = graph.nearbySegments(lat, lon, CANDIDATE_RADIUS_METERS);
            if (nearby.isEmpty()) {
                nearby = graph.nearbySegments(lat, lon, CANDIDATE_FALLBACK_RADIUS_METERS);
            }
            nearby.sort(Comparator.comparingDouble(s -> s.distanceTo(lat, lon)));
            if (nearby.size() > MAX_CANDIDATES_PER_POINT) {
                nearby = new ArrayList<>(nearby.subList(0, MAX_CANDIDATES_PER_POINT));
            }

            CandidateSet set = new CandidateSet();
            set.index = i;
            set.rawPoint = raw;
            set.lat = lat;
            set.lon = lon;
            set.headingDeg = raw.getHeadingDeg() == null ? estimateHeading(windowPoints, i) : raw.getHeadingDeg().doubleValue();
            set.candidates = new ArrayList<>();

            for (RoadSegment segment : nearby) {
                Candidate candidate = new Candidate();
                candidate.segment = segment;
                candidate.projection = projectToSegment(segment, lat, lon);
                candidate.distanceMeters = candidate.projection.getDistanceMeters();
                candidate.segmentHeadingDeg = candidate.projection.getLocalDirectionDegrees();
                candidate.headingDeltaDeg = angularDiff(set.headingDeg, candidate.segmentHeadingDeg);
                set.candidates.add(candidate);
            }

            computeComplexity(set, graph);
            sets.add(set);
        }
        return sets;
    }

    private void computeComplexity(CandidateSet set, RoadGraph graph) {
        if (set.candidates.isEmpty()) {
            set.directionComplexity = 0.0;
            set.connectivityComplexity = 0.0;
            set.complexityScore = 0.0;
            return;
        }

        // 更贴近论文：基于候选方向角差的离散程度，离散越小（越平行）则方向复杂度越高。
        List<Double> pairDiffs = new ArrayList<>();
        for (int i = 0; i < set.candidates.size(); i++) {
            for (int j = i + 1; j < set.candidates.size(); j++) {
                pairDiffs.add(angularDiff(set.candidates.get(i).segmentHeadingDeg, set.candidates.get(j).segmentHeadingDeg));
            }
        }
        double stdDeg = Math.sqrt(variance(pairDiffs));
        double dz = stdDeg / 30.0;
        set.directionComplexity = clamp01(1.0 - 0.5 * dz);

        // 更贴近论文：邻域节点平均度与候选总路段数共同表征连通复杂度。
        Set<Long> touchedNodes = new LinkedHashSet<>();
        int maxDegree = 0;
        double degreeSum = 0.0;
        for (Candidate candidate : set.candidates) {
            touchedNodes.add(candidate.segment.getStartNodeId());
            touchedNodes.add(candidate.segment.getEndNodeId());
        }
        for (Long nodeId : touchedNodes) {
            int degree = Optional.ofNullable(graph.getJunctions().get(nodeId)).map(RoadJunction::getDegree).orElse(0);
            maxDegree = Math.max(maxDegree, degree);
            degreeSum += degree;
        }
        double avgDegree = touchedNodes.isEmpty() ? 0.0 : degreeSum / touchedNodes.size();
        double k = Math.max(1.0, set.candidates.size());
        set.connectivityComplexity = clamp01((1.0 + avgDegree / k) * 0.5);
        set.maxNodeDegree = maxDegree;
        set.directionStdDegrees = stdDeg;

        set.complexityScore = Math.max(set.directionComplexity, set.connectivityComplexity);
    }

    private double firstOrderObservationProbability(Candidate candidate) {
        double pDist = gaussian(candidate.distanceMeters, 0.0, SIGMA_DISTANCE_METERS);
        double pHeading = gaussian(candidate.headingDeltaDeg, 0.0, SIGMA_HEADING_DEGREES);
        return clampProbability(pDist * pHeading);
    }

    private double firstOrderTransitionProbability(TrackPoint prevPoint,
                                                   Candidate prev,
                                                   TrackPoint currPoint,
                                                   Candidate curr,
                                                   RoadGraph graph) {
        double observed = greatCircle(prevPoint, currPoint);
        double route = projectedRouteDistance(prev, curr, graph);
        double dt = Math.abs(observed - route);
        double pDt = exponential(dt, BETA_TRANSITION_METERS);

        double theta = angularDiff(prev.segmentHeadingDeg, curr.segmentHeadingDeg);
        double pTheta = Math.exp(-LAMBDA_DIRECTION * Math.toRadians(theta));
        double pStructure = structuralTransitionProbability(prev, curr, graph);
        return clampProbability(pDt * pTheta * pStructure);
    }

    private Map<Long, Double> secondOrderStartProbabilities(CandidateSet firstSet) {
        Map<Long, Double> result = new HashMap<>();
        double sum = 0.0;
        for (Candidate candidate : firstSet.candidates) {
            double d = Math.max(1.0, candidate.distanceMeters);
            double theta = Math.max(1.0, candidate.headingDeltaDeg);
            double p = 1.0 / (d * theta);
            result.put(candidate.segment.getSegmentId(), p);
            sum += p;
        }
        if (sum <= 0.0) {
            return result;
        }
        for (Map.Entry<Long, Double> e : new ArrayList<>(result.entrySet())) {
            result.put(e.getKey(), clampProbability(e.getValue() / sum));
        }
        return result;
    }

    private double secondOrderPairObservation(TrackPoint prevPoint,
                                              Candidate prev,
                                              TrackPoint currPoint,
                                              Candidate curr,
                                              RoadGraph graph) {
        // 按论文中 P(O_{t-1}, O_t | C_{t-1}, C_t) 的思想：
        // 采用前一点观测 * 当前点观测 * 一阶转移
        return clampProbability(
                firstOrderObservationProbability(prev)
                        * firstOrderObservationProbability(curr)
                        * firstOrderTransitionProbability(prevPoint, prev, currPoint, curr, graph)
        );
    }

    private double secondOrderTransitionProbability(TrackPoint prevPrevPoint,
                                                    Candidate prevPrev,
                                                    TrackPoint prevPoint,
                                                    Candidate prev,
                                                    TrackPoint currPoint,
                                                    Candidate curr,
                                                    RoadGraph graph) {
        double observedTwoStep = greatCircle(prevPrevPoint, prevPoint) + greatCircle(prevPoint, currPoint);
        double routeTwoStep = projectedRouteDistance(prevPrev, prev, graph)
                + projectedRouteDistance(prev, curr, graph);
        double kt = Math.abs(observedTwoStep - routeTwoStep);
        double pStructure = structuralTransitionProbability(prevPrev, prev, graph)
                * structuralTransitionProbability(prev, curr, graph);
        return clampProbability(exponential(kt, SECOND_ORDER_LAMBDA_METERS) * pStructure);
    }

    private double adaptiveBlend(double observationPair, double secondOrderTransition, double complexity) {
        double f = clamp01(complexity);
        double a = (1.0 - f) * clampProbability(observationPair);
        double b = f * clampProbability(secondOrderTransition);
        double denominator = a + b;
        if (denominator <= MIN_PROB) {
            return MIN_PROB;
        }
        return clampProbability((a * b) / denominator);
    }


    private double firstOrderContinuityPrior(Candidate prevWindowCandidate, Candidate current, RoadGraph graph) {
        double route = projectedRouteDistance(prevWindowCandidate, current, graph);
        double pRoute = exponential(route, BETA_TRANSITION_METERS * 3.0);
        double pStructure = structuralTransitionProbability(prevWindowCandidate, current, graph);
        return clampProbability(pRoute * pStructure);
    }

    private double projectedRouteDistance(Candidate from, Candidate to, RoadGraph graph) {
        if (from == null || to == null || from.segment == null || to.segment == null || graph == null) {
            return 1_000_000.0;
        }
        if (from.projection == null) {
            from.projection = projectToSegment(from.segment, from.segment.getGeometry().get(0).getLat(), from.segment.getGeometry().get(0).getLon());
        }
        if (to.projection == null) {
            to.projection = projectToSegment(to.segment, to.segment.getGeometry().get(0).getLat(), to.segment.getGeometry().get(0).getLon());
        }
        String key = "P:" + from.segment.getSegmentId() + ":" + Math.round(from.projection.getOffsetFromStartMeters())
                + "->" + to.segment.getSegmentId() + ":" + Math.round(to.projection.getOffsetFromStartMeters());
        Double cached = routeDistanceCache.get(key);
        if (cached != null) {
            return cached;
        }

        double result;
        if (from.segment.getSegmentId() == to.segment.getSegmentId()) {
            double delta = to.projection.getOffsetFromStartMeters() - from.projection.getOffsetFromStartMeters();
            result = delta >= 0 ? delta : from.segment.getLengthMeters() + Math.abs(delta);
        } else {
            double leaveCost = Math.max(0.0, from.segment.getLengthMeters() - from.projection.getOffsetFromStartMeters());
            double enterCost = Math.max(0.0, to.projection.getOffsetFromStartMeters());
            double middle = restrictedShortestPathDistance(graph, from.segment, to.segment);
            if (!Double.isFinite(middle)) {
                middle = greatCircle(from.projection.getLat(), from.projection.getLon(), to.projection.getLat(), to.projection.getLon()) * 8.0;
            }
            result = leaveCost + middle + enterCost;
        }
        routeDistanceCache.put(key, result);
        return result;
    }

    private double structuralTransitionProbability(Candidate prev, Candidate curr, RoadGraph graph) {
        double p = 1.0;
        if (prev.segment.getLayer() != curr.segment.getLayer() && !prev.segment.isRamp() && !curr.segment.isRamp()) {
            p *= 0.65;
        }
        if (prev.segment.isBridge() != curr.segment.isBridge()) {
            p *= 0.85;
        }
        if (prev.segment.isTunnel() != curr.segment.isTunnel()) {
            p *= 0.85;
        }
        if (prev.segment.isRamp() != curr.segment.isRamp()) {
            double theta = angularDiff(prev.segmentHeadingDeg, curr.segmentHeadingDeg);
            p *= theta <= 45.0 ? 0.95 : 0.70;
        }
        if (violatesImmediateRestriction(prev.segment, curr.segment, graph)) {
            return MIN_PROB;
        }
        return clampProbability(p);
    }

    private boolean violatesImmediateRestriction(RoadSegment prev, RoadSegment curr, RoadGraph graph) {
        Long viaNode = prev.getEndNodeId();
        List<Long> history = Collections.singletonList(prev.getOsmWayId());
        return violatesRestriction(history, viaNode, curr.getOsmWayId(), graph.getRestrictions());
    }

    private double restrictedShortestPathDistance(RoadGraph graph, RoadSegment fromSegment, RoadSegment toSegment) {
        if (fromSegment.getEndNodeId() == toSegment.getStartNodeId()) {
            if (!violatesImmediateRestriction(fromSegment, toSegment, graph)) {
                return 0.0;
            }
        }
        int maxHistory = maxRestrictionHistory(graph.getRestrictions());
        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparingDouble(s -> s.distance));
        Map<String, Double> best = new HashMap<>();
        LinkedList<Long> initHistory = new LinkedList<>();
        initHistory.add(fromSegment.getOsmWayId());
        RouteState start = new RouteState(fromSegment.getEndNodeId(), initHistory, 0.0);
        pq.offer(start);
        best.put(start.key(), 0.0);

        while (!pq.isEmpty()) {
            RouteState state = pq.poll();
            if (state.distance > best.getOrDefault(state.key(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            for (RoadSegment next : graph.outgoing(state.nodeId)) {
                if (violatesRestriction(state.wayHistory, state.nodeId, next.getOsmWayId(), graph.getRestrictions())) {
                    continue;
                }
                double nd = state.distance + next.getLengthMeters();
                LinkedList<Long> nextHistory = new LinkedList<>(state.wayHistory);
                nextHistory.add(next.getOsmWayId());
                while (nextHistory.size() > maxHistory) {
                    nextHistory.removeFirst();
                }
                if (next.getSegmentId() == toSegment.getSegmentId()) {
                    return nd;
                }
                RouteState nextState = new RouteState(next.getEndNodeId(), nextHistory, nd);
                if (nd < best.getOrDefault(nextState.key(), Double.POSITIVE_INFINITY)) {
                    best.put(nextState.key(), nd);
                    pq.offer(nextState);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private int maxRestrictionHistory(List<RoadRestriction> restrictions) {
        int max = 2;
        for (RoadRestriction restriction : restrictions) {
            max = Math.max(max, 1 + restriction.getViaWayIds().size());
        }
        return max;
    }

    private boolean violatesRestriction(List<Long> wayHistory, Long viaNodeId, long nextWayId,
                                        List<RoadRestriction> restrictions) {
        for (RoadRestriction restriction : restrictions) {
            if (restriction == null || restriction.getType() == null) {
                continue;
            }
            if (restriction.getExceptModes() != null && !restriction.getExceptModes().isBlank()) {
                continue;
            }
            String type = restriction.getType().toLowerCase(Locale.ROOT);
            List<Long> pattern = new ArrayList<>();
            pattern.add(restriction.getFromWayId());
            pattern.addAll(restriction.getViaWayIds());
            boolean historyMatched = suffixMatches(wayHistory, pattern);
            boolean viaNodeMatched = restriction.getViaNodeId() == null || Objects.equals(restriction.getViaNodeId(), viaNodeId);
            if (!historyMatched || !viaNodeMatched) {
                continue;
            }
            if (type.startsWith("no_") && nextWayId == restriction.getToWayId()) {
                return true;
            }
            if (type.startsWith("only_") && nextWayId != restriction.getToWayId()) {
                return true;
            }
        }
        return false;
    }

    private boolean suffixMatches(List<Long> history, List<Long> pattern) {
        if (pattern.isEmpty()) {
            return true;
        }
        if (history == null || history.size() < pattern.size()) {
            return false;
        }
        int offset = history.size() - pattern.size();
        for (int i = 0; i < pattern.size(); i++) {
            if (!Objects.equals(history.get(offset + i), pattern.get(i))) {
                return false;
            }
        }
        return true;
    }

    private Candidate buildSeedCandidate(Long segmentId, RoadGraph graph, Double lat, Double lon) {
        if (segmentId == null || graph == null) {
            return null;
        }
        RoadSegment segment = graph.getSegments().get(segmentId);
        if (segment == null) {
            return null;
        }
        Candidate candidate = new Candidate();
        candidate.segment = segment;
        double refLat = lat != null ? lat : segment.getGeometry().get(0).getLat();
        double refLon = lon != null ? lon : segment.getGeometry().get(0).getLon();
        candidate.projection = projectToSegment(segment, refLat, refLon);
        candidate.distanceMeters = candidate.projection.getDistanceMeters();
        candidate.segmentHeadingDeg = candidate.projection.getLocalDirectionDegrees();
        candidate.headingDeltaDeg = 0.0;
        return candidate;
    }

    private WindowSeed updateSeedFromResults(WindowSeed base,
                                             List<MapMatchingResult> results,
                                             List<CandidateSet> candidateSets,
                                             int startInclusive,
                                             int endInclusive) {
        WindowSeed seed = base == null ? WindowSeed.empty() : base.copy();
        if (results == null || results.isEmpty()) {
            return seed;
        }
        for (MapMatchingResult result : results) {
            if (result.getMatchedRoadId() == null) {
                continue;
            }
            seed.secondLastSegmentId = seed.lastSegmentId;
            seed.secondLastMatchedLat = seed.lastMatchedLat;
            seed.secondLastMatchedLon = seed.lastMatchedLon;
            seed.lastSegmentId = result.getMatchedRoadId();
            seed.lastMatchedLat = result.getMatchedLatitude();
            seed.lastMatchedLon = result.getMatchedLongitude();
        }
        return seed;
    }

    private Candidate findCandidateBySegmentId(CandidateSet set, Long segmentId) {
        if (set == null || segmentId == null) {
            return null;
        }
        for (Candidate candidate : set.candidates) {
            if (candidate.segment.getSegmentId() == segmentId) {
                return candidate;
            }
        }
        return null;
    }

    private MapMatchingResult toResult(CandidateSet set, Candidate matched, int position) {
        MapMatchingResult result = new MapMatchingResult();
        result.setTrackPointId(set.rawPoint.getId());
        result.setPosition(position);

        if (matched == null) {
            result.setMatchedLatitude(set.lat);
            result.setMatchedLongitude(set.lon);
            result.setConfidence(0.05);
            return result;
        }

        Projection projection = matched.projection == null ? projectToSegment(matched.segment, set.lat, set.lon) : matched.projection;
        result.setMatchedLatitude(projection.getLat());
        result.setMatchedLongitude(projection.getLon());
        result.setMatchedRoadId(matched.segment.getSegmentId());
        result.setMatchedSegmentId(matched.segment.getSegmentId());
        result.setMatchedWayId(matched.segment.getOsmWayId());
        result.setMatchedRoadName(matched.segment.getName());
        result.setConfidence(Math.max(0.05, 1.0 / (1.0 + matched.distanceMeters)));
        return result;
    }

    private Projection projectToSegment(RoadSegment segment, double lat, double lon) {
        List<GeoCoord> geometry = segment.getGeometry();
        if (geometry.size() < 2) {
            return new Projection(lat, lon, 0.0, 0.0, 0.0);
        }

        double bestDistance = Double.POSITIVE_INFINITY;
        double bestLat = lat;
        double bestLon = lon;
        double bestOffset = 0.0;
        double prefix = 0.0;
        double bestDirection = segmentHeading(segment);

        for (int i = 0; i < geometry.size() - 1; i++) {
            GeoCoord a = geometry.get(i);
            GeoCoord b = geometry.get(i + 1);

            SegmentProjection proj = projectToSegment(lat, lon, a, b);
            if (proj.distanceMeters < bestDistance) {
                bestDistance = proj.distanceMeters;
                bestLat = proj.projectedLat;
                bestLon = proj.projectedLon;
                bestOffset = prefix + proj.offsetMeters;
                bestDirection = bearingDegrees(a.getLat(), a.getLon(), b.getLat(), b.getLon());
            }
            prefix += greatCircle(a.getLat(), a.getLon(), b.getLat(), b.getLon());
        }

        return new Projection(bestLat, bestLon, bestDistance, bestOffset, bestDirection);
    }

    private SegmentProjection projectToSegment(double lat, double lon, GeoCoord a, GeoCoord b) {
        double meanLatRad = Math.toRadians((lat + a.getLat() + b.getLat()) / 3.0);
        double meterPerLat = 111_320.0;
        double meterPerLon = Math.cos(meanLatRad) * 111_320.0;

        double px = lon * meterPerLon;
        double py = lat * meterPerLat;
        double x1 = a.getLon() * meterPerLon;
        double y1 = a.getLat() * meterPerLat;
        double x2 = b.getLon() * meterPerLon;
        double y2 = b.getLat() * meterPerLat;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 0.0) {
            return new SegmentProjection(a.getLat(), a.getLon(), greatCircle(lat, lon, a.getLat(), a.getLon()), 0.0);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return new SegmentProjection(projY / meterPerLat, projX / meterPerLon, Math.hypot(px - projX, py - projY), Math.sqrt(lenSq) * t);
    }

    private List<MapMatchingResult> projectRaw(List<TrackPoint> windowPoints,
                                               RoadGraph graph,
                                               int startInclusive,
                                               int endInclusive,
                                               int globalWindowStart) {
        log.warn("[MAP_MATCH_FALLBACK_RAW] globalStart={} range={}..{} reason=no_usable_candidates", globalWindowStart, startInclusive, endInclusive);
        List<MapMatchingResult> results = new ArrayList<>();
        for (int i = startInclusive; i <= endInclusive; i++) {
            TrackPoint p = windowPoints.get(i);
            double lat = decodeDouble(p.getLatEnc());
            double lon = decodeDouble(p.getLngEnc());

            List<RoadSegment> nearby = graph == null ? Collections.emptyList() : graph.nearbySegments(lat, lon, CANDIDATE_FALLBACK_RADIUS_METERS);
            RoadSegment best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (RoadSegment segment : nearby) {
                double d = segment.distanceTo(lat, lon);
                if (d < bestDist) {
                    bestDist = d;
                    best = segment;
                }
            }

            CandidateSet set = new CandidateSet();
            set.rawPoint = p;
            set.lat = lat;
            set.lon = lon;
            Candidate candidate = null;
            if (best != null) {
                candidate = new Candidate();
                candidate.segment = best;
                candidate.projection = projectToSegment(best, lat, lon);
                candidate.distanceMeters = bestDist;
                candidate.segmentHeadingDeg = candidate.projection.getLocalDirectionDegrees();
                candidate.headingDeltaDeg = 0.0;
            }
            results.add(toResult(set, candidate, globalWindowStart + i));
        }
        return results;
    }

    private void appendDedup(List<MapMatchingResult> target, List<MapMatchingResult> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        if (target.isEmpty()) {
            target.addAll(source);
            return;
        }
        int start = 0;
        if (target.get(target.size() - 1).getTrackPointId() != null && source.get(0).getTrackPointId() != null
                && Objects.equals(target.get(target.size() - 1).getTrackPointId(), source.get(0).getTrackPointId())) {
            start = 1;
        }
        for (int i = start; i < source.size(); i++) {
            target.add(source.get(i));
        }
    }

    private void appendWindowResultsWithOverlapDedup(List<MapMatchingResult> merged,
                                                     List<MapMatchingResult> windowResults,
                                                     RoadGraphService.LoadContext loadContext,
                                                     RoadGraphService.Window window) {
        if (windowResults == null || windowResults.isEmpty()) {
            return;
        }
        if (merged.isEmpty()) {
            merged.addAll(windowResults);
            log.warn("[MAP_MATCH_WINDOW_STITCH] window={} mode=INIT appended={}", window == null ? -1 : window.getIndex(), windowResults.size());
            return;
        }

        Map<Long, Integer> mergedIndexByTrackId = new HashMap<>();
        for (int i = 0; i < merged.size(); i++) {
            Long trackPointId = merged.get(i).getTrackPointId();
            if (trackPointId != null) {
                mergedIndexByTrackId.put(trackPointId, i);
            }
        }

        int firstCommonMergedIdx = -1;
        int firstCommonNewIdx = -1;
        Long spliceTrackPointId = null;
        for (int i = 0; i < windowResults.size(); i++) {
            Long trackPointId = windowResults.get(i).getTrackPointId();
            if (trackPointId == null) {
                continue;
            }
            Integer mergedIdx = mergedIndexByTrackId.get(trackPointId);
            if (mergedIdx != null) {
                firstCommonMergedIdx = mergedIdx;
                firstCommonNewIdx = i;
                spliceTrackPointId = trackPointId;
                break;
            }
        }

        if (firstCommonMergedIdx < 0) {
            int before = merged.size();
            appendDedup(merged, windowResults);
            log.warn("[MAP_MATCH_WINDOW_STITCH] window={} mode=APPEND_NO_OVERLAP before={} after={} appended={}",
                    window == null ? -1 : window.getIndex(),
                    before,
                    merged.size(),
                    Math.max(0, merged.size() - before));
            return;
        }

        int removedOld = merged.size() - firstCommonMergedIdx;
        while (merged.size() > firstCommonMergedIdx) {
            merged.remove(merged.size() - 1);
        }
        for (int i = firstCommonNewIdx; i < windowResults.size(); i++) {
            merged.add(windowResults.get(i));
        }

        log.warn("[MAP_MATCH_WINDOW_STITCH] window={} mode=REPLACE_OVERLAP spliceTrackPointId={} mergedCutIndex={} newStartIndex={} removedOld={} appendedNew={} finalSize={}",
                window == null ? -1 : window.getIndex(),
                spliceTrackPointId,
                firstCommonMergedIdx,
                firstCommonNewIdx,
                removedOld,
                windowResults.size() - firstCommonNewIdx,
                merged.size());
    }

    private WindowSeed buildCarrySeedForNextWindow(List<MapMatchingResult> windowResults,
                                                   RoadGraphService.Window currentWindow,
                                                   RoadGraphService.Window nextWindow) {
        if (windowResults == null || windowResults.isEmpty()) {
            return WindowSeed.empty();
        }
        if (nextWindow == null) {
            WindowSeed tailSeed = buildTailSeed(windowResults);
            log.warn("[MAP_MATCH_CARRY_SEED] currentWindow={} nextWindow=- mode=TAIL secondLast={} last={}",
                    currentWindow == null ? -1 : currentWindow.getIndex(),
                    tailSeed.secondLastSegmentId,
                    tailSeed.lastSegmentId);
            return tailSeed;
        }

        int anchorLastPos = nextWindow.getStartIndex() - 1;
        int anchorSecondLastPos = nextWindow.getStartIndex() - 2;
        MapMatchingResult secondLast = findResultByPosition(windowResults, anchorSecondLastPos);
        MapMatchingResult last = findResultByPosition(windowResults, anchorLastPos);

        if (last == null || last.getMatchedRoadId() == null) {
            WindowSeed tailSeed = buildTailSeed(windowResults);
            log.warn("[MAP_MATCH_CARRY_SEED] currentWindow={} nextWindow={} mode=TAIL_FALLBACK nextStart={} secondLast={} last={}",
                    currentWindow == null ? -1 : currentWindow.getIndex(),
                    nextWindow.getIndex(),
                    nextWindow.getStartIndex(),
                    tailSeed.secondLastSegmentId,
                    tailSeed.lastSegmentId);
            return tailSeed;
        }

        WindowSeed seed = new WindowSeed();
        if (secondLast != null && secondLast.getMatchedRoadId() != null) {
            seed.secondLastSegmentId = secondLast.getMatchedRoadId();
            seed.secondLastMatchedLat = secondLast.getMatchedLatitude();
            seed.secondLastMatchedLon = secondLast.getMatchedLongitude();
        }
        seed.lastSegmentId = last.getMatchedRoadId();
        seed.lastMatchedLat = last.getMatchedLatitude();
        seed.lastMatchedLon = last.getMatchedLongitude();

        log.warn("[MAP_MATCH_CARRY_SEED] currentWindow={} nextWindow={} mode=ANCHOR_BEFORE_NEXT nextStart={} secondLastPos={} lastPos={} secondLast={} last={}",
                currentWindow == null ? -1 : currentWindow.getIndex(),
                nextWindow.getIndex(),
                nextWindow.getStartIndex(),
                anchorSecondLastPos,
                anchorLastPos,
                seed.secondLastSegmentId,
                seed.lastSegmentId);
        return seed;
    }

    private WindowSeed buildTailSeed(List<MapMatchingResult> results) {
        WindowSeed seed = WindowSeed.empty();
        if (results == null || results.isEmpty()) {
            return seed;
        }
        for (MapMatchingResult result : results) {
            if (result.getMatchedRoadId() == null) {
                continue;
            }
            seed.secondLastSegmentId = seed.lastSegmentId;
            seed.secondLastMatchedLat = seed.lastMatchedLat;
            seed.secondLastMatchedLon = seed.lastMatchedLon;
            seed.lastSegmentId = result.getMatchedRoadId();
            seed.lastMatchedLat = result.getMatchedLatitude();
            seed.lastMatchedLon = result.getMatchedLongitude();
        }
        return seed;
    }

    private MapMatchingResult findResultByPosition(List<MapMatchingResult> results, int position) {
        if (results == null) {
            return null;
        }
        for (MapMatchingResult result : results) {
            if (result.getPosition() != null && result.getPosition() == position) {
                return result;
            }
        }
        return null;
    }

    private boolean touchesBoundary(List<MapMatchingResult> results, RoadGraphService.Window window) {
        if (results == null || results.isEmpty() || window == null || window.getHaloBox() == null) {
            return false;
        }
        RoadGraphService.BBox halo = window.getHaloBox();
        double minLat = readBBoxField(halo, "minLat");
        double maxLat = readBBoxField(halo, "maxLat");
        double minLon = readBBoxField(halo, "minLon");
        double maxLon = readBBoxField(halo, "maxLon");
        if (!Double.isFinite(minLat) || !Double.isFinite(maxLat) || !Double.isFinite(minLon) || !Double.isFinite(maxLon)) {
            return false;
        }
        for (MapMatchingResult r : results) {
            if (r.getMatchedLatitude() == null || r.getMatchedLongitude() == null) {
                continue;
            }
            double lat = r.getMatchedLatitude();
            double lon = r.getMatchedLongitude();
            double minGap = Math.min(
                    Math.min(Math.abs(lat - minLat) * 111_000.0, Math.abs(lat - maxLat) * 111_000.0),
                    Math.min(Math.abs(lon - minLon) * 111_000.0, Math.abs(lon - maxLon) * 111_000.0)
            );
            if (minGap <= 80.0) {
                return true;
            }
        }
        return false;
    }

    private double readBBoxField(RoadGraphService.BBox bbox, String fieldName) {
        try {
            java.lang.reflect.Field field = bbox.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(bbox);
            return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    @Override
    public List<Map<String, Object>> generateMockTrackPoints(Long tripId) {
        return new ArrayList<>();
    }

    @Override
    public Map<String, TrackPolylineVO> processTrackPoints(Long tripId, List<Map<String, Object>> originalPoints) {
        List<TrackPoint> rawTrackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        List<MapMatchingResult> matched = getLatestMatchedCache(tripId);
        CoordTypeVO displayCoordType = resolveDisplayCoordType(rawTrackPoints);

        TrackPolylineVO rawPolyline = buildRawPolyline(rawTrackPoints, displayCoordType);
        TrackPolylineVO matchedPolyline = buildMatchedPolyline(matched, displayCoordType);
        TrackPolylineVO reconstructedPolyline = buildReconstructedPolyline(matched, displayCoordType);

        Map<String, TrackPolylineVO> result = new HashMap<>();
        result.put("rawPolyline", rawPolyline);
        result.put("matchedPolyline", matchedPolyline);
        result.put("reconstructedPolyline", reconstructedPolyline);
        return result;
    }

    private TrackPolylineVO buildRawPolyline(List<TrackPoint> rawTrackPoints, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        if (rawTrackPoints != null) {
            for (TrackPoint point : rawTrackPoints) {
                double lat = decodeDouble(point.getLatEnc());
                double lon = decodeDouble(point.getLngEnc());
                CoordType rawType = point.getRawCoordType();
                double[] display = toDisplayCoord(lat, lon, rawType, displayCoordType);
                points.add(GeoPointVO.builder().lat(display[0]).lng(display[1]).coordType(displayCoordType).build());
            }
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(false).build();
    }

    private TrackPolylineVO buildMatchedPolyline(List<MapMatchingResult> results, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        for (MapMatchingResult result : results) {
            if (result.getMatchedLatitude() == null || result.getMatchedLongitude() == null) {
                continue;
            }
            double[] display = fromInternalWgs84ToDisplay(result.getMatchedLatitude(), result.getMatchedLongitude(), displayCoordType);
            points.add(GeoPointVO.builder().lat(display[0]).lng(display[1]).coordType(displayCoordType).build());
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(false).build();
    }

    private TrackPolylineVO buildReconstructedPolyline(List<MapMatchingResult> results, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        Double lastLat = null;
        Double lastLon = null;
        for (MapMatchingResult result : results) {
            if (result.getMatchedLatitude() == null || result.getMatchedLongitude() == null) {
                continue;
            }
            double[] display = fromInternalWgs84ToDisplay(result.getMatchedLatitude(), result.getMatchedLongitude(), displayCoordType);
            if (lastLat != null && Math.abs(lastLat - display[0]) < 1e-7 && Math.abs(lastLon - display[1]) < 1e-7) {
                continue;
            }
            points.add(GeoPointVO.builder().lat(display[0]).lng(display[1]).coordType(displayCoordType).build());
            lastLat = display[0];
            lastLon = display[1];
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(false).build();
    }

    private CoordTypeVO resolveDisplayCoordType(List<TrackPoint> rawTrackPoints) {
        // 小程序地图统一按 GCJ02 显示；原始点无论数据库里是 WGS84 还是 GCJ02，返回前端前都统一转成 GCJ02。
        return CoordTypeVO.GCJ02;
    }

    private double[] toDisplayCoord(double lat, double lon, CoordType sourceType, CoordTypeVO displayType) {
        if (displayType == CoordTypeVO.GCJ02) {
            if (sourceType != null && "GCJ02".equalsIgnoreCase(sourceType.name())) {
                return new double[]{lat, lon};
            }
            return wgs84ToGcj02(lat, lon);
        }
        if (sourceType != null && "GCJ02".equalsIgnoreCase(sourceType.name())) {
            return gcj02ToWgs84(lat, lon);
        }
        return new double[]{lat, lon};
    }

    private double[] fromInternalWgs84ToDisplay(double lat, double lon, CoordTypeVO displayType) {
        if (displayType == CoordTypeVO.GCJ02) {
            return wgs84ToGcj02(lat, lon);
        }
        return new double[]{lat, lon};
    }


    public void markTripMatchDirty(Long tripId) {
        if (tripId == null) {
            return;
        }
        redisService.setString(dirtyKey(tripId), "1", MATCH_DIRTY_TTL_SECONDS);
        log.warn("[TRACK_MATCH_DIRTY] mark tripId={}", tripId);
    }

    public boolean recomputeTripMatchIfNeeded(Long tripId) {
        if (tripId == null) {
            return false;
        }
        String lockKey = lockKey(tripId);
        String lockValue = UUID.randomUUID().toString();
        if (!redisService.setIfAbsent(lockKey, lockValue, MATCH_LOCK_TTL_SECONDS)) {
            log.debug("[TRACK_MATCH_RECOMPUTE] skip locked tripId={}", tripId);
            return false;
        }
        try {
            List<TrackPoint> before = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
            if (before == null || before.isEmpty()) {
                latestMatchMemoryCache.remove(latestKey(tripId));
                redisService.deleteKey(latestKey(tripId));
                redisService.deleteKey(fingerprintKey(tripId));
                redisService.deleteKey(dirtyKey(tripId));
                return false;
            }
            String startFingerprint = buildFingerprint(tripId, before);
            String cachedFingerprint = redisService.getString(fingerprintKey(tripId));
            boolean dirty = redisService.hasKey(dirtyKey(tripId));
            if (!dirty && Objects.equals(startFingerprint, cachedFingerprint)) {
                return false;
            }
            List<MapMatchingResult> results = matchTrajectory(before);
            List<TrackPoint> after = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
            String endFingerprint = buildFingerprint(tripId, after);
            if (!Objects.equals(startFingerprint, endFingerprint)) {
                markTripMatchDirty(tripId);
                log.warn("[TRACK_MATCH_RECOMPUTE] fingerprint changed during recompute, skip overwrite tripId={} startFp={} endFp={}", tripId, startFingerprint, endFingerprint);
                return false;
            }
            LatestMatchPayload payload = new LatestMatchPayload();
            payload.tripId = tripId;
            payload.pointCount = after.size();
            payload.lastTs = lastTimestamp(after);
            payload.algoVersion = MATCH_ALGO_VERSION;
            payload.fingerprint = endFingerprint;
            payload.generatedAt = System.currentTimeMillis();
            payload.results = resequenceCopy(results);
            saveLatestMatchPayload(tripId, payload);
            redisService.setString(fingerprintKey(tripId), endFingerprint, MATCH_LATEST_TTL_SECONDS);
            redisService.deleteKey(dirtyKey(tripId));
            return true;
        } finally {
            releaseLockSafely(lockKey, lockValue);
        }
    }

    public List<MapMatchingResult> getLatestMatchedCache(Long tripId) {
        LatestMatchPayload payload = loadLatestMatchPayload(tripId);
        if (payload == null || payload.results == null || payload.results.isEmpty()) {
            return new ArrayList<>();
        }
        if (!Objects.equals(MATCH_ALGO_VERSION, payload.algoVersion)) {
            markTripMatchDirty(tripId);
            log.warn("[TRACK_MATCH_LATEST] algo version mismatch tripId={} payloadAlgo={} currentAlgo={}", tripId, payload.algoVersion, MATCH_ALGO_VERSION);
            return new ArrayList<>();
        }
        return resequenceCopy(payload.results);
    }

    public String getMatchAlgoVersion() {
        return MATCH_ALGO_VERSION;
    }

    public String buildCurrentTripFingerprint(Long tripId) {
        List<TrackPoint> raw = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        return buildFingerprint(tripId, raw);
    }

    public TripRouteSnapshotPayload buildRouteSnapshotPayload(Long tripId) {
        List<TrackPoint> rawTrackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        List<MapMatchingResult> matched = getLatestMatchedCache(tripId);
        TripRouteSnapshotPayload payload = new TripRouteSnapshotPayload();
        payload.setTripId(tripId);
        payload.setAlgoVersion(MATCH_ALGO_VERSION);
        payload.setFingerprint(buildFingerprint(tripId, rawTrackPoints));
        payload.setPointCount(rawTrackPoints == null ? 0 : rawTrackPoints.size());
        payload.setStartTs(rawTrackPoints == null || rawTrackPoints.isEmpty() ? null : rawTrackPoints.get(0).getTs());
        payload.setEndTs(rawTrackPoints == null || rawTrackPoints.isEmpty() ? null : rawTrackPoints.get(rawTrackPoints.size() - 1).getTs());
        payload.setGeneratedAt(System.currentTimeMillis());
        payload.setMatchedResults(resequenceCopy(matched));
        payload.setMatchedPolyline(buildMatchedPolyline(matched, CoordTypeVO.GCJ02));
        payload.setReconstructedPolyline(buildReconstructedPolyline(matched, CoordTypeVO.GCJ02));
        return payload;
    }

    public void warmLatestCacheFromSnapshot(TripRouteSnapshotPayload payload) {
        if (payload == null || payload.getTripId() == null) {
            return;
        }
        LatestMatchPayload cache = new LatestMatchPayload();
        cache.tripId = payload.getTripId();
        cache.pointCount = payload.getPointCount() == null ? 0 : payload.getPointCount();
        cache.lastTs = payload.getEndTs() == null ? 0L : payload.getEndTs();
        cache.algoVersion = payload.getAlgoVersion() == null ? MATCH_ALGO_VERSION : payload.getAlgoVersion();
        cache.fingerprint = payload.getFingerprint();
        cache.generatedAt = payload.getGeneratedAt() == null ? System.currentTimeMillis() : payload.getGeneratedAt();
        cache.results = resequenceCopy(payload.getMatchedResults() == null ? Collections.emptyList() : payload.getMatchedResults());
        saveLatestMatchPayload(payload.getTripId(), cache);
        if (payload.getFingerprint() != null && !payload.getFingerprint().isBlank()) {
            redisService.setString(fingerprintKey(payload.getTripId()), payload.getFingerprint(), MATCH_LATEST_TTL_SECONDS);
        }
    }

    private String latestKey(Long tripId) {
        return MATCH_LATEST_KEY_PREFIX + tripId;
    }

    private String dirtyKey(Long tripId) {
        return MATCH_DIRTY_KEY_PREFIX + tripId;
    }

    private String fingerprintKey(Long tripId) {
        return MATCH_FINGERPRINT_KEY_PREFIX + tripId;
    }

    private String lockKey(Long tripId) {
        return MATCH_LOCK_KEY_PREFIX + tripId;
    }

    private String buildFingerprint(Long tripId, List<TrackPoint> raw) {
        return tripId + ":" + pointCount(raw) + ":" + lastTimestamp(raw) + ":" + MATCH_ALGO_VERSION;
    }

    private int pointCount(List<TrackPoint> raw) {
        return raw == null ? 0 : raw.size();
    }

    private long lastTimestamp(List<TrackPoint> raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        TrackPoint last = raw.get(raw.size() - 1);
        return last == null || last.getTs() == null ? 0L : last.getTs();
    }

    private LatestMatchPayload loadLatestMatchPayload(Long tripId) {
        String key = latestKey(tripId);
        LatestMatchPayload memory = latestMatchMemoryCache.get(key);
        if (memory != null) {
            return copyLatestPayload(memory);
        }
        try {
            String json = redisService.getString(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            LatestMatchPayload payload = objectMapper.readValue(json, LatestMatchPayload.class);
            if (payload != null) {
                latestMatchMemoryCache.put(key, copyLatestPayload(payload));
            }
            return payload;
        } catch (Exception e) {
            log.warn("[TRACK_MATCH_LATEST] load failed tripId={}: {}", tripId, e.getMessage(), e);
            return null;
        }
    }

    private void saveLatestMatchPayload(Long tripId, LatestMatchPayload payload) {
        if (payload == null) {
            return;
        }
        String key = latestKey(tripId);
        latestMatchMemoryCache.put(key, copyLatestPayload(payload));
        try {
            redisService.setString(key, objectMapper.writeValueAsString(payload), MATCH_LATEST_TTL_SECONDS);
        } catch (Exception e) {
            log.warn("[TRACK_MATCH_LATEST] save failed tripId={}: {}", tripId, e.getMessage(), e);
        }
    }

    private LatestMatchPayload copyLatestPayload(LatestMatchPayload payload) {
        LatestMatchPayload copy = new LatestMatchPayload();
        copy.tripId = payload.tripId;
        copy.pointCount = payload.pointCount;
        copy.lastTs = payload.lastTs;
        copy.algoVersion = payload.algoVersion;
        copy.fingerprint = payload.fingerprint;
        copy.generatedAt = payload.generatedAt;
        copy.results = resequenceCopy(payload.results);
        return copy;
    }

    private void releaseLockSafely(String lockKey, String expectedValue) {
        try {
            String currentValue = redisService.getString(lockKey);
            if (Objects.equals(expectedValue, currentValue)) {
                redisService.deleteKey(lockKey);
            }
        } catch (Exception e) {
            log.warn("[TRACK_MATCH_LOCK] release failed lockKey={}: {}", lockKey, e.getMessage(), e);
        }
    }

    private List<MapMatchingResult> resequenceCopy(List<MapMatchingResult> source) {
        List<MapMatchingResult> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (MapMatchingResult result : source) {
            copy.add(copyResult(result));
        }
        resequence(copy);
        return copy;
    }

    private MapMatchingResult copyResult(MapMatchingResult source) {
        MapMatchingResult copy = new MapMatchingResult();
        copy.setTrackPointId(source.getTrackPointId());
        copy.setPosition(source.getPosition());
        copy.setMatchedLatitude(source.getMatchedLatitude());
        copy.setMatchedLongitude(source.getMatchedLongitude());
        copy.setMatchedRoadId(source.getMatchedRoadId());
        copy.setMatchedRoadName(source.getMatchedRoadName());
        copy.setMatchedSegmentId(source.getMatchedSegmentId());
        copy.setMatchedWayId(source.getMatchedWayId());
        copy.setConfidence(source.getConfidence());
        return copy;
    }

    private void shiftPositions(List<MapMatchingResult> results, int offset) {
        if (results == null) {
            return;
        }
        for (MapMatchingResult result : results) {
            result.setPosition((result.getPosition() == null ? 0 : result.getPosition()) + offset);
        }
    }

    private long argMax(Map<Long, Double> scores) {
        long bestKey = -1L;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Long, Double> e : scores.entrySet()) {
            if (e.getValue() > bestValue) {
                bestValue = e.getValue();
                bestKey = e.getKey();
            }
        }
        return bestKey;
    }

    private StateKey argMaxState(Map<StateKey, Double> scores) {
        StateKey best = null;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (Map.Entry<StateKey, Double> e : scores.entrySet()) {
            if (e.getValue() > bestValue) {
                bestValue = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private String resolveRoadResource(List<GeoCoord> trajectoryWgs84) {
        if (trajectoryWgs84 == null || trajectoryWgs84.isEmpty()) {
            return CHINA_PBF;
        }
        GeoCoord first = trajectoryWgs84.get(0);
        if (first.getLat() >= 31.4 && first.getLat() <= 36.4 && first.getLon() >= 110.4 && first.getLon() <= 116.6) {
            return HENAN_PBF;
        }
        return CHINA_PBF;
    }

    private List<GeoCoord> toGeoCoords(List<TrackPoint> points) {
        List<GeoCoord> result = new ArrayList<>();
        for (TrackPoint point : points) {
            result.add(new GeoCoord(decodeDouble(point.getLatEnc()), decodeDouble(point.getLngEnc())));
        }
        return result;
    }

    private List<TrackPoint> normalizeTrackPointsToWgs84(List<TrackPoint> points) {
        List<TrackPoint> normalized = new ArrayList<>();
        for (TrackPoint point : sortByTimestamp(points)) {
            TrackPoint copy = copyTrackPoint(point);
            double lat = decodeDouble(point.getLatEnc());
            double lon = decodeDouble(point.getLngEnc());
            double[] wgs = toWgs84(lat, lon, point.getRawCoordType());
            copy.setLatEnc(encodeDouble(wgs[0]));
            copy.setLngEnc(encodeDouble(wgs[1]));
            normalized.add(copy);
        }
        return normalized;
    }

    private double[] toWgs84(double lat, double lon, CoordType coordType) {
        String type = coordType == null ? "" : coordType.name();
        if ("GCJ02".equalsIgnoreCase(type)) {
            return gcj02ToWgs84(lat, lon);
        }
        return new double[]{lat, lon};
    }

    private List<TrackPoint> sortByTimestamp(List<TrackPoint> points) {
        List<TrackPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(p -> p.getTs() == null ? 0L : p.getTs()));
        return sorted;
    }

    private TrackPoint copyTrackPoint(TrackPoint source) {
        TrackPoint copy = new TrackPoint();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setTripId(source.getTripId());
        copy.setTs(source.getTs());
        copy.setLatEnc(source.getLatEnc());
        copy.setLngEnc(source.getLngEnc());
        copy.setAccuracyM(source.getAccuracyM());
        copy.setSpeedMps(source.getSpeedMps());
        copy.setHeadingDeg(source.getHeadingDeg());
        copy.setSource(source.getSource());
        copy.setRawCoordType(source.getRawCoordType());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private double routeDistance(RoadSegment a, RoadSegment b, RoadGraph graph) {
        if (a == null || b == null || graph == null) {
            return 1_000_000.0;
        }
        String key = a.getSegmentId() + "->" + b.getSegmentId();
        Double cached = routeDistanceCache.get(key);
        if (cached != null) {
            return cached;
        }

        double result;
        if (a.getSegmentId() == b.getSegmentId()) {
            result = Math.min(a.getLengthMeters(), 5.0);
        } else {
            result = shortestPathDistance(graph, a.getEndNodeId(), b.getStartNodeId());
            if (!Double.isFinite(result)) {
                result = greatCircle(a.getGeometry().get(a.getGeometry().size() - 1), b.getGeometry().get(0)) * 10.0;
            }
        }
        routeDistanceCache.put(key, result);
        return result;
    }

    private double shortestPathDistance(RoadGraph graph, long startNodeId, long endNodeId) {
        if (startNodeId == endNodeId) {
            return 0.0;
        }

        Map<Long, Double> dist = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.distance));

        dist.put(startNodeId, 0.0);
        pq.offer(new NodeDistance(startNodeId, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            if (!visited.add(current.nodeId)) {
                continue;
            }
            if (current.nodeId == endNodeId) {
                return current.distance;
            }

            for (RoadSegment outgoing : graph.outgoing(current.nodeId)) {
                long next = outgoing.getEndNodeId();
                if (visited.contains(next)) {
                    continue;
                }
                double nd = current.distance + outgoing.getLengthMeters();
                if (nd < dist.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    dist.put(next, nd);
                    pq.offer(new NodeDistance(next, nd));
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private double estimateHeading(List<TrackPoint> points, int index) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }
        int prev = Math.max(0, index - 1);
        int next = Math.min(points.size() - 1, index + 1);
        double lat1 = decodeDouble(points.get(prev).getLatEnc());
        double lon1 = decodeDouble(points.get(prev).getLngEnc());
        double lat2 = decodeDouble(points.get(next).getLatEnc());
        double lon2 = decodeDouble(points.get(next).getLngEnc());
        return bearingDegrees(lat1, lon1, lat2, lon2);
    }

    private double segmentHeading(RoadSegment segment) {
        List<GeoCoord> geometry = segment.getGeometry();
        if (geometry.size() < 2) {
            return 0.0;
        }
        GeoCoord a = geometry.get(0);
        GeoCoord b = geometry.get(Math.min(1, geometry.size() - 1));
        return bearingDegrees(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    private double greatCircle(TrackPoint a, TrackPoint b) {
        return greatCircle(decodeDouble(a.getLatEnc()), decodeDouble(a.getLngEnc()), decodeDouble(b.getLatEnc()), decodeDouble(b.getLngEnc()));
    }

    private double greatCircle(GeoCoord a, GeoCoord b) {
        return greatCircle(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    private double greatCircle(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);
        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double aa = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(aa), Math.sqrt(1.0 - aa));
        return 6371000.0 * c;
    }

    private double gaussian(double x, double mean, double sigma) {
        sigma = Math.max(1e-3, sigma);
        double exponent = -Math.pow(x - mean, 2.0) / (2.0 * sigma * sigma);
        return Math.exp(exponent) / (Math.sqrt(2.0 * Math.PI) * sigma);
    }

    private double exponential(double value, double beta) {
        beta = Math.max(1e-3, beta);
        return clampProbability(Math.exp(-value / beta) / beta);
    }

    private double clampProbability(double p) {
        if (Double.isNaN(p) || Double.isInfinite(p)) {
            return MIN_PROB;
        }
        return Math.max(MIN_PROB, p);
    }

    private double safeLog(double p) {
        return Math.log(clampProbability(p));
    }

    private double variance(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(v -> v).average().orElse(0.0);
        double sum = 0.0;
        for (double v : values) {
            sum += Math.pow(v - mean, 2.0);
        }
        return sum / values.size();
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double angularDiff(double a, double b) {
        double diff = Math.abs(a - b) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    private double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2)
                - Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return bearing < 0 ? bearing + 360.0 : bearing;
    }

    private double decodeDouble(byte[] bytes) {
        long bits = 0L;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private byte[] encodeDouble(double value) {
        return encodeDoubleStatic(value);
    }

    private static byte[] encodeDoubleStatic(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (bits >> (i * 8));
        }
        return bytes;
    }

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] delta = delta(lat, lon);
        return new double[]{lat + delta[0], lon + delta[1]};
    }

    private double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] delta = delta(lat, lon);
        double mgLat = lat + delta[0];
        double mgLon = lon + delta[1];
        return new double[]{lat * 2 - mgLat, lon * 2 - mgLon};
    }

    private boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private double[] delta(double lat, double lon) {
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{dLat, dLon};
    }

    private double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    private void logWindowGraph(RoadGraphService.Window window, RoadGraph graph) {
        if (window == null || graph == null) {
            return;
        }
        log.warn("[MAP_MATCH_WINDOW_GRAPH] window={} pointRange={}..{} segments={} junctions={} restrictions={} loadedTiles={} headingDeg={}",
                window.getIndex(), window.getStartIndex(), window.getEndIndex(),
                safeSize(graph.getSegments()), safeSize(graph.getJunctions()), safeSize(graph.getRestrictions()),
                "-", window.getHeadingDegrees() == null ? "-" : String.format(Locale.ROOT, "%.1f", window.getHeadingDegrees()));
    }

    private void logCandidateSets(RoadGraphService.Window window, List<CandidateSet> candidateSets) {
        if (candidateSets == null) {
            return;
        }
        for (CandidateSet set : candidateSets) {
            if (!shouldLogDetailedPoint(window == null ? -1 : window.getIndex(), set)) {
                continue;
            }
            StringJoiner joiner = new StringJoiner(" | ");
            int limit = Math.min(DEBUG_CANDIDATE_TOP_K, set.candidates == null ? 0 : set.candidates.size());
            for (int i = 0; i < limit; i++) {
                Candidate c = set.candidates.get(i);
                joiner.add(describeCandidate(c));
            }
            log.warn("[MAP_MATCH_CANDIDATES] window={} localIdx={} raw=({}, {}) heading={} complex={}/dir={}/conn={} maxDegree={} count={} -> {}",
                    window == null ? -1 : window.getIndex(),
                    set.index,
                    round6(set.lat),
                    round6(set.lon),
                    round1(set.headingDeg),
                    round3(set.complexityScore),
                    round3(set.directionComplexity),
                    round3(set.connectivityComplexity),
                    set.maxNodeDegree,
                    set.candidates == null ? 0 : set.candidates.size(),
                    joiner.length() == 0 ? "NO_CANDIDATE" : joiner.toString());
        }
    }

    private void logSegmentPlans(RoadGraphService.Window window, List<SegmentPlan> plans) {
        if (plans == null) {
            return;
        }
        for (SegmentPlan plan : plans) {
            log.warn("[MAP_MATCH_SEGMENT_PLAN] window={} range={}..{} mode={}",
                    window == null ? -1 : window.getIndex(),
                    plan.startInclusive,
                    plan.endInclusive,
                    plan.complexSegment ? "SECOND_ORDER_COMPLEX" : "FIRST_ORDER_SIMPLE");
        }
    }

    private void logTopCandidateScores(String tag, int t, Map<Long, Double> scores, Map<Long, Long> backPointer, CandidateSet currentSet, RoadGraph graph) {
        Integer windowIndex = currentWindowIndex.get();
        if (scores == null || scores.isEmpty() || currentSet == null || currentSet.candidates == null || !shouldLogDetailedPoint(windowIndex == null ? -1 : windowIndex, currentSet)) {
            return;
        }
        List<Map.Entry<Long, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringJoiner joiner = new StringJoiner(" | ");
        int limit = Math.min(DEBUG_CANDIDATE_TOP_K, entries.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<Long, Double> entry = entries.get(i);
            Candidate curr = findCandidateBySegmentId(currentSet, entry.getKey());
            Long prevId = backPointer == null ? null : backPointer.get(entry.getKey());
            RoadSegment prev = prevId == null || graph == null ? null : graph.getSegments().get(prevId);
            joiner.add("score=" + round3(entry.getValue()) + ",curr=" + describeCandidate(curr) + ",bestPrev=" + (prev == null ? prevId : describeSegment(prev)));
        }
        log.warn("{} t={} top={}", tag, t, joiner.toString());
    }

    private void logTopStateScores(String tag, int t, Map<StateKey, Double> scores, Map<StateKey, StateKey> backPointer, RoadGraph graph) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        Integer windowIndex = currentWindowIndex.get();
        List<Map.Entry<StateKey, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.removeIf(entry -> !shouldLogDetailedState(windowIndex == null ? -1 : windowIndex, entry.getKey(), graph));
        if (entries.isEmpty()) {
            return;
        }
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringJoiner joiner = new StringJoiner(" | ");
        int limit = Math.min(DEBUG_CANDIDATE_TOP_K, entries.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<StateKey, Double> entry = entries.get(i);
            StateKey state = entry.getKey();
            StateKey prevState = backPointer == null ? null : backPointer.get(state);
            RoadSegment prevSeg = graph == null ? null : graph.getSegments().get(state.prevSegmentId);
            RoadSegment currSeg = graph == null ? null : graph.getSegments().get(state.currSegmentId);
            joiner.add("score=" + round3(entry.getValue())
                    + ",pair=[" + (prevSeg == null ? state.prevSegmentId : describeSegment(prevSeg))
                    + " -> " + (currSeg == null ? state.currSegmentId : describeSegment(currSeg))
                    + "]"
                    + ",bestPrevPair=" + (prevState == null ? "null" : (prevState.prevSegmentId + "->" + prevState.currSegmentId)));
        }
        log.warn("{} t={} top={}", tag, t, joiner.toString());
    }

    private boolean shouldLogDetailedPoint(int windowIndex, CandidateSet set) {
        if (set == null) {
            return false;
        }
        boolean windowMatch = DEBUG_TARGET_WINDOW < 0 || windowIndex == DEBUG_TARGET_WINDOW;
        boolean pointRangeMatch = DEBUG_POINT_FROM < 0 || (set.index >= DEBUG_POINT_FROM && set.index <= DEBUG_POINT_TO);
        boolean targetWayHit = containsTargetWay(set);
        boolean targeted = (windowMatch && pointRangeMatch) || targetWayHit;
        if (!targeted) {
            return false;
        }
        if (!DEBUG_ONLY_ABNORMAL) {
            return true;
        }
        return targetWayHit || isAbnormalCandidateSet(set);
    }

    private boolean shouldLogDetailedState(int windowIndex, StateKey state, RoadGraph graph) {
        boolean windowMatch = DEBUG_TARGET_WINDOW < 0 || windowIndex == DEBUG_TARGET_WINDOW;
        if (!windowMatch || state == null || graph == null) {
            return false;
        }
        RoadSegment prev = graph.getSegments().get(state.prevSegmentId);
        RoadSegment curr = graph.getSegments().get(state.currSegmentId);
        boolean targetWayHit = isTargetWay(prev) || isTargetWay(curr);
        if (!DEBUG_ONLY_ABNORMAL) {
            return targetWayHit;
        }
        return targetWayHit;
    }

    private boolean containsTargetWay(CandidateSet set) {
        if (set == null || set.candidates == null) {
            return false;
        }
        for (Candidate candidate : set.candidates) {
            if (candidate != null && isTargetWay(candidate.segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTargetWay(RoadSegment segment) {
        return segment != null && DEBUG_TARGET_WAY_IDS.contains(segment.getOsmWayId());
    }

    private boolean isAbnormalCandidateSet(CandidateSet set) {
        if (set == null || set.candidates == null || set.candidates.isEmpty()) {
            return true;
        }
        Candidate best = set.candidates.get(0);
        if (best.distanceMeters > 20.0 || best.headingDeltaDeg > 35.0) {
            return true;
        }
        if (set.candidates.size() >= 2) {
            Candidate second = set.candidates.get(1);
            if (Math.abs(second.distanceMeters - best.distanceMeters) <= 5.0
                    && Math.abs(second.headingDeltaDeg - best.headingDeltaDeg) <= 10.0) {
                return true;
            }
        }
        return false;
    }

    private void logChosenRoute(String tag, String scope, List<MapMatchingResult> results, RoadGraph graph) {
        if (results == null || results.isEmpty()) {
            log.warn("{} scope={} route=EMPTY", tag, scope);
            return;
        }
        List<Long> segmentIds = collapseMatchedSegmentIds(results);
        List<Long> wayIds = collapseMatchedWayIds(segmentIds, graph);
        log.warn("{} scope={} pointCount={} uniqueSegments={} uniqueWays={} segmentChain={}",
                tag,
                scope,
                results.size(),
                segmentIds.size(),
                wayIds.size(),
                joinSegmentChain(segmentIds, graph));
        log.warn("{} scope={} osmWayChain={}", tag, scope, wayIds.isEmpty() ? "[]" : wayIds.toString());
    }

    private void logConnectivitySummary(String tag, List<MapMatchingResult> results, RoadGraph graph) {
        if (results == null || results.isEmpty() || graph == null) {
            log.warn("{} result=SKIP reason=empty_or_no_graph", tag);
            return;
        }
        List<Long> segmentIds = collapseMatchedSegmentIds(results);
        if (segmentIds.size() <= 1) {
            log.warn("{} pairs=0 disconnectedPairs=0 missingSegments=0", tag);
            return;
        }
        int disconnectedPairs = 0;
        int missingSegments = 0;
        for (int i = 1; i < segmentIds.size(); i++) {
            Long prevId = segmentIds.get(i - 1);
            Long currId = segmentIds.get(i);
            RoadSegment prev = graph.getSegments().get(prevId);
            RoadSegment curr = graph.getSegments().get(currId);
            if (prev == null || curr == null) {
                missingSegments++;
                log.warn("{} step={} connected=MISSING prev={} curr={}", tag, i, prevId, currId);
                continue;
            }
            boolean connected;
            double routeMeters;
            if (Objects.equals(prevId, currId)) {
                connected = true;
                routeMeters = 0.0;
            } else {
                routeMeters = restrictedShortestPathDistance(graph, prev, curr);
                connected = Double.isFinite(routeMeters);
            }
            if (!connected) {
                disconnectedPairs++;
            }
            log.warn("{} step={} connected={} routeMeters={} prev={} curr={}",
                    tag,
                    i,
                    connected,
                    Double.isFinite(routeMeters) ? round2(routeMeters) : "INF",
                    describeSegment(prev),
                    describeSegment(curr));
        }
        log.warn("{} pairs={} disconnectedPairs={} missingSegments={}", tag, Math.max(0, segmentIds.size() - 1), disconnectedPairs, missingSegments);
    }

    private List<Long> collapseMatchedSegmentIds(List<MapMatchingResult> results) {
        List<Long> ids = new ArrayList<>();
        Long last = null;
        for (MapMatchingResult result : results) {
            Long id = result.getMatchedRoadId();
            if (id == null) {
                continue;
            }
            if (!Objects.equals(last, id)) {
                ids.add(id);
                last = id;
            }
        }
        return ids;
    }

    private List<Long> collapseMatchedWayIds(List<Long> segmentIds, RoadGraph graph) {
        List<Long> wayIds = new ArrayList<>();
        Long lastWay = null;
        for (Long segmentId : segmentIds) {
            RoadSegment segment = graph == null || segmentId == null ? null : graph.getSegments().get(segmentId);
            if (segment == null) {
                continue;
            }
            Long wayId = segment.getOsmWayId();
            if (!Objects.equals(lastWay, wayId)) {
                wayIds.add(wayId);
                lastWay = wayId;
            }
        }
        return wayIds;
    }

    private String joinSegmentChain(List<Long> segmentIds, RoadGraph graph) {
        if (segmentIds == null || segmentIds.isEmpty()) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        int limit = Math.min(LOG_ROUTE_PREVIEW_LIMIT, segmentIds.size());
        for (int i = 0; i < limit; i++) {
            RoadSegment segment = graph == null ? null : graph.getSegments().get(segmentIds.get(i));
            items.add(segment == null ? String.valueOf(segmentIds.get(i)) : describeSegment(segment));
        }
        if (segmentIds.size() > limit) {
            items.add("...(total=" + segmentIds.size() + ")");
        }
        return items.toString();
    }

    private String describeCandidate(Candidate c) {
        if (c == null || c.segment == null) {
            return "null";
        }
        return "seg=" + c.segment.getSegmentId()
                + "/way=" + c.segment.getOsmWayId()
                + "/name=" + safeName(c.segment.getName())
                + "/dist=" + round2(c.distanceMeters)
                + "/headDiff=" + round1(c.headingDeltaDeg)
                + "/layer=" + c.segment.getLayer()
                + "/ramp=" + c.segment.isRamp()
                + "/bridge=" + c.segment.isBridge()
                + "/tunnel=" + c.segment.isTunnel();
    }

    private String describeSegment(RoadSegment segment) {
        if (segment == null) {
            return "null";
        }
        return "seg=" + segment.getSegmentId()
                + "/way=" + segment.getOsmWayId()
                + "/name=" + safeName(segment.getName())
                + "/" + safeName(segment.getHighwayType())
                + "/" + segment.getStartNodeId() + "->" + segment.getEndNodeId();
    }

    private int safeSize(Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }

    private int safeSize(Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private void resequence(List<MapMatchingResult> merged) {
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setPosition(i);
        }
    }

    public static class LatestMatchPayload {
        public Long tripId;
        public int pointCount;
        public long lastTs;
        public String algoVersion;
        public String fingerprint;
        public long generatedAt;
        public List<MapMatchingResult> results;
    }

    private static class SegmentPlan {
        final int startInclusive;
        final int endInclusive;
        final boolean complexSegment;

        private SegmentPlan(int startInclusive, int endInclusive, boolean complexSegment) {
            this.startInclusive = startInclusive;
            this.endInclusive = endInclusive;
            this.complexSegment = complexSegment;
        }
    }

    private static class CandidateSet {
        int index;
        TrackPoint rawPoint;
        double lat;
        double lon;
        double headingDeg;
        List<Candidate> candidates;
        double directionComplexity;
        double connectivityComplexity;
        double complexityScore;
        double directionStdDegrees;
        int maxNodeDegree;
    }

    private static class Candidate {
        RoadSegment segment;
        Projection projection;
        double distanceMeters;
        double segmentHeadingDeg;
        double headingDeltaDeg;
    }

    private static class WindowSeed {
        Long secondLastSegmentId;
        Long lastSegmentId;
        Double secondLastMatchedLat;
        Double secondLastMatchedLon;
        Double lastMatchedLat;
        Double lastMatchedLon;

        static WindowSeed empty() {
            return new WindowSeed();
        }

        WindowSeed copy() {
            WindowSeed w = new WindowSeed();
            w.secondLastSegmentId = this.secondLastSegmentId;
            w.lastSegmentId = this.lastSegmentId;
            w.secondLastMatchedLat = this.secondLastMatchedLat;
            w.secondLastMatchedLon = this.secondLastMatchedLon;
            w.lastMatchedLat = this.lastMatchedLat;
            w.lastMatchedLon = this.lastMatchedLon;
            return w;
        }

        TrackPoint toTrackPoint(boolean last) {
            TrackPoint point = new TrackPoint();
            point.setLatEnc(TrackPointServiceImpl.encodeDoubleStatic(last ? safeCoord(lastMatchedLat) : safeCoord(secondLastMatchedLat)));
            point.setLngEnc(TrackPointServiceImpl.encodeDoubleStatic(last ? safeCoord(lastMatchedLon) : safeCoord(secondLastMatchedLon)));
            return point;
        }

        private double safeCoord(Double value) {
            return value == null ? 0.0 : value;
        }
    }

    private static class RouteState {
        final long nodeId;
        final LinkedList<Long> wayHistory;
        final double distance;

        private RouteState(long nodeId, LinkedList<Long> wayHistory, double distance) {
            this.nodeId = nodeId;
            this.wayHistory = wayHistory;
            this.distance = distance;
        }

        private String key() {
            return nodeId + "|" + wayHistory.toString();
        }
    }

    private static class StateKey {
        final long prevSegmentId;
        final long currSegmentId;

        private StateKey(long prevSegmentId, long currSegmentId) {
            this.prevSegmentId = prevSegmentId;
            this.currSegmentId = currSegmentId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof StateKey other)) {
                return false;
            }
            return prevSegmentId == other.prevSegmentId && currSegmentId == other.currSegmentId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(prevSegmentId, currSegmentId);
        }
    }

    private static class NodeDistance {
        final long nodeId;
        final double distance;

        private NodeDistance(long nodeId, double distance) {
            this.nodeId = nodeId;
            this.distance = distance;
        }
    }

    private static class SegmentProjection {
        final double projectedLat;
        final double projectedLon;
        final double distanceMeters;
        final double offsetMeters;

        private SegmentProjection(double projectedLat, double projectedLon, double distanceMeters, double offsetMeters) {
            this.projectedLat = projectedLat;
            this.projectedLon = projectedLon;
            this.distanceMeters = distanceMeters;
            this.offsetMeters = offsetMeters;
        }
    }
}
