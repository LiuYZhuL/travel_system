package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.dto.*;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.TrackPointSource;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.service.TrackPointService;
import com.travel.travel_system.service.pub.RoadNetworkService;
import com.travel.travel_system.utils.RoadNetworkCoverageInspector;
import com.travel.travel_system.vo.GeoPointVO;
import com.travel.travel_system.vo.TrackPolylineVO;
import com.travel.travel_system.vo.enums.CoordTypeVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class TrackPointServiceImpl implements TrackPointService {

    /**
     * 已按当前项目 TrackPoint / TrackPointService / TrackPolylineVO 结构对齐。
     */

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private RoadNetworkService roadNetworkService;

    /**
     * 论文中邻域半径实验结论最优约为 40m，因此候选搜索优先从 40m 开始逐步扩展。
     */
    private static final double[] CANDIDATE_SEARCH_RADII_METERS = {40.0, 80.0, 120.0};
    private static final double[] CANDIDATE_FALLBACK_RADII_METERS = {180.0, 260.0};
    private static final double COMPLEXITY_RADIUS_METERS = 40.0;
    private static final double EXTENDED_RADIUS_MAX_HEADING_DIFF = 35.0;
    private static final double EXTENDED_RADIUS_OBSERVATION_PENALTY = 0.72;
    private static final double ROAD_SNAP_FALLBACK_METERS = 80.0;

    /** 观测 / 转移参数 */
    private static final double SIGMA_D_METERS = 20.0;
    private static final double SIGMA_THETA_DEGREES = 45.0;
    private static final double ROUTE_BETA_METERS = 100.0;
    private static final double SECOND_ORDER_BETA_METERS = 120.0;
    private static final double ANGLE_LAMBDA = 2.0;
    private static final double PARALLEL_ROAD_ANGLE_DEGREES = 18.0;
    private static final double PARALLEL_ROAD_SWITCH_PENALTY = 0.55;
    private static final double PARALLEL_ROAD_SEPARATION_METERS = 10.0;
    private static final double PARALLEL_ROAD_OBSERVATION_METERS = 15.0;
    private static final double CURVE_CONTINUITY_ANGLE_DEGREES = 45.0;
    private static final double INTERSECTION_SHARP_TURN_DEGREES = 55.0;
    private static final double INTERSECTION_CROSSING_PENALTY = 0.35;
    private static final double INTERSECTION_STRAIGHT_BONUS = 1.25;
    private static final double CURVE_CONTINUITY_BONUS = 1.15;
    private static final double MIN_PROB = 1e-12;
    private static final double NEG_INF = -1e30;

    /** 候选与分段 */
    private static final int SIMPLE_MAX_CANDIDATES = 4;
    private static final int COMPLEX_MAX_CANDIDATES = 6;
    private static final int COMPLEX_CONTEXT_POINTS = 2;
    private static final int MAX_SECOND_ORDER_SEGMENT_POINTS = 36;
    private static final double DIRECTION_COMPLEX_THRESHOLD = 0.68;
    private static final double OVERALL_COMPLEX_THRESHOLD = 0.72;
    private static final int COMPLEX_NODE_DEGREE_THRESHOLD = 4;

    /** 驾车轨迹预处理阈值 */
    private static final double MAX_DRIVING_SPEED_MPS = 35.0;      // 论文预处理图中上限约 35m/s
    private static final double MIN_MOVING_SPEED_MPS = 1.0;        // 论文预处理图中低速阈值约 1m/s
    private static final double MAX_STEP_DISTANCE_METERS = 250.0;  // 论文预处理图中相邻点距离阈值约 0.25km
    private static final double MAX_ACCURACY_METERS = 120.0;
    private static final long MIN_TIME_DELTA_MS = 1000L;

    /** 多模态分类 / 步行处理 */
    private static final double GENERAL_MAX_ACCURACY_METERS = 200.0;
    private static final double WALKING_MAX_ACCURACY_METERS = 180.0;
    private static final double WALKING_MAX_REASONABLE_SPEED_MPS = 4.2;
    private static final double WALKING_SPIKE_SPEED_MPS = 7.5;
    private static final double WALKING_NEAR_ROAD_METERS = 18.0;
    private static final double WALKING_OFF_ROAD_METERS = 30.0;
    private static final int MOTION_CLASSIFY_WINDOW_RADIUS = 2;
    private static final int MIN_MODE_SEGMENT_POINTS = 4;
    private static final long MIN_MODE_SEGMENT_DURATION_MS = 45_000L;
    private static final double DRIVE_CLASSIFY_MEDIAN_SPEED_MPS = 3.8;
    private static final double DRIVE_CLASSIFY_STRONG_SPEED_MPS = 6.5;
    private static final double WALK_CLASSIFY_STRONG_SPEED_MPS = 2.2;
    private static final double EXTREME_JUMP_SPEED_MPS = 55.0;
    private static final int WALKING_SMOOTH_RADIUS = 2;
    private static final double WALKING_STAY_RADIUS_METERS = 10.0;
    private static final long WALKING_STAY_MIN_DURATION_MS = 15_000L;
    private static final double WALKING_STAY_MAX_SPEED_MPS = 1.2;
    private static final double WALKING_SIMPLIFY_MIN_MOVE_METERS = 4.0;
    private static final long WALKING_SIMPLIFY_MAX_IDLE_GAP_MS = 30_000L;

    private static final String MODE_DRIVING = "DRIVING";
    private static final String MODE_WALKING = "WALKING";
    private static final String MODE_UNKNOWN = "UNKNOWN";

    private static final String MATCHED_DRIVING_COLOR = "#FF9500";
    private static final String MATCHED_WALKING_COLOR = "#34C759";
    private static final String RECONSTRUCTED_DRIVING_COLOR = "#007AFF";
    private static final String RECONSTRUCTED_WALKING_COLOR = "#22C55E";

    private static final CoordType INTERNAL_COORD_TYPE = CoordType.WGS84;
    private static final CoordType DISPLAY_COORD_TYPE = CoordType.GCJ02;

    /** 停留压缩阈值，避免停车抖动干扰匹配 */
    private static final double STAY_CLUSTER_RADIUS_METERS = 12.0;
    private static final long STAY_CLUSTER_MIN_DURATION_MS = 20_000L;
    private static final double STAY_CLUSTER_MAX_SPEED_MPS = 1.5;

    /** 路径重构 */
    private static final int LINEAR_INTERPOLATION_STEPS = 8;
    private static final double MAX_RECONSTRUCTION_DETOUR_RATIO = 3.2;
    private static final double MAX_RECONSTRUCTION_EXTRA_METERS = 220.0;
    private static final double LOCAL_DISCONNECT_HARD_BLOCK_METERS = 120.0;
    private static final double LAYER_STRUCTURE_SWITCH_PENALTY = 0.42;
    private static final double LAYER_STRUCTURE_KEEP_BONUS = 1.06;
    private static final double RAMP_TRANSITION_BONUS = 1.05;
    private static final double SAME_NAMED_CORRIDOR_BONUS = 1.18;
    private static final double UNNAMED_SURFACE_EXIT_PENALTY = 0.68;
    private static final double SAME_CORRIDOR_FALLBACK_MAX_ANGLE = 25.0;
    private static final double SAME_CORRIDOR_FALLBACK_MAX_DIRECT_METERS = 120.0;
    private static final double DIRECT_NODE_HANDOFF_MAX_METERS = 35.0;
    private static final double SAME_WAY_NODE_HANDOFF_MAX_METERS = 90.0;
    private static final int MAX_RECONSTRUCTION_NODE_COUNT = 12;

    /** 运行期缓存 */
    private static final int NEARBY_EDGE_CACHE_BUCKET = 20_000;
    private static final double OFFSET_CACHE_BUCKET_METERS = 5.0;
    private static final int MAX_NEARBY_EDGE_CACHE_SIZE = 4096;
    private static final int MAX_ROUTE_DISTANCE_CACHE_SIZE = 20_000;

    private final ThreadLocal<MatchingRuntimeContext> matchingContext = new ThreadLocal<>();

    private <T> T withMatchingContext(Supplier<T> supplier) {
        MatchingRuntimeContext existing = matchingContext.get();
        if (existing != null) {
            existing.depth++;
            try {
                return supplier.get();
            } finally {
                existing.depth--;
            }
        }

        MatchingRuntimeContext created = new MatchingRuntimeContext();
        created.depth = 1;
        matchingContext.set(created);
        try {
            return supplier.get();
        } finally {
            matchingContext.remove();
        }
    }

    private MatchingRuntimeContext currentMatchingContext() {
        return matchingContext.get();
    }

    @Override
    public void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return;
        }
        trackPointRepository.saveAll(trackPoints);
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
        int radius = 2;

        for (int i = 0; i < sorted.size(); i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(sorted.size() - 1, i + radius);

            double weightSum = 0.0;
            double latSum = 0.0;
            double lonSum = 0.0;

            for (int j = from; j <= to; j++) {
                TrackPoint neighbor = sorted.get(j);
                double distanceWeight = 1.0 / (1.0 + Math.abs(i - j));
                latSum += bytesToDoubleSafe(neighbor.getLatEnc()) * distanceWeight;
                lonSum += bytesToDoubleSafe(neighbor.getLngEnc()) * distanceWeight;
                weightSum += distanceWeight;
            }

            TrackPoint source = sorted.get(i);
            TrackPoint copy = copyTrackPoint(source);
            copy.setLatEnc(doubleToBytes(latSum / Math.max(weightSum, 1.0)));
            copy.setLngEnc(doubleToBytes(lonSum / Math.max(weightSum, 1.0)));
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
            List<TrackPoint> normalized = normalizeTrackPointsToInternalWgs84(trackPoints);
            List<TrackPoint> prepared = preprocessDrivingTrack(normalized);
            if (prepared.isEmpty()) {
                return new ArrayList<>();
            }

            RoadNetwork roadNetwork = loadRoadNetwork(prepared);
            return matchTrajectory(prepared, roadNetwork);
        });
    }

    public List<MapMatchingResult> matchTrajectory(List<TrackPoint> trackPoints, RoadNetwork roadNetwork) {
        return withMatchingContext(() -> {
            if (trackPoints == null || trackPoints.isEmpty()) {
                return new ArrayList<>();
            }
            List<TrackPoint> normalized = normalizeTrackPointsToInternalWgs84(trackPoints);
            if (roadNetwork == null || roadNetwork.getEdgeCount() == 0) {
                return simpleProjection(normalized);
            }
            return matchAdaptiveHmm(normalized, roadNetwork);
        });
    }

    private RoadNetwork loadRoadNetwork(List<TrackPoint> trackPoints) {
        BBox bbox = computeTrackBBox(trackPoints);
        double centerLat = (bbox.minLat + bbox.maxLat) / 2.0;
        double centerLon = (bbox.minLon + bbox.maxLon) / 2.0;
        double diagonalMeters = calculateGreatCircleDistance(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon);
        double radiusKm = Math.max(1.5, diagonalMeters / 2000.0 + 1.0);
        return roadNetworkService.getRoadNetwork(centerLat, centerLon, radiusKm);
    }

    private List<MapMatchingResult> matchAdaptiveHmm(List<TrackPoint> originalPoints, RoadNetwork roadNetwork) {
        if (originalPoints.size() == 1) {
            return simpleProjection(originalPoints);
        }

        List<CompressedWindow> windows = compressStayWindows(originalPoints);
        List<TrackPoint> representativePoints = new ArrayList<>(windows.size());
        for (CompressedWindow window : windows) {
            representativePoints.add(window.representative);
        }

        if (representativePoints.size() == 1) {
            return expandWindowResults(windows, simpleProjection(representativePoints));
        }

        List<PointComplexity> complexityList = calculateComplexities(representativePoints, roadNetwork);
        List<TrajectorySegment> segments = segmentTrajectory(representativePoints, complexityList);

        Map<Integer, MapMatchingResult> resultByWindowIndex = new HashMap<>();
        for (TrajectorySegment segment : segments) {
            List<MapMatchingResult> matched;
            if (segment.complex) {
                matched = matchComplexSegment(segment, roadNetwork);
            } else {
                matched = matchSimpleSegment(segment, roadNetwork);
            }

            for (int i = 0; i < matched.size(); i++) {
                resultByWindowIndex.put(segment.startWindowIndex + i, matched.get(i));
            }
        }

        List<MapMatchingResult> representativeResults = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            MapMatchingResult matched = resultByWindowIndex.get(i);
            if (matched == null) {
                matched = buildRawFallbackResult(windows.get(i).representative, i);
            }
            matched.setPosition(i);
            representativeResults.add(matched);
        }

        return expandWindowResults(windows, representativeResults);
    }

    private List<MapMatchingResult> expandWindowResults(List<CompressedWindow> windows,
                                                        List<MapMatchingResult> representativeResults) {
        List<MapMatchingResult> expanded = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) {
            CompressedWindow window = windows.get(i);
            MapMatchingResult base = representativeResults.get(i);
            for (TrackPoint member : window.members) {
                MapMatchingResult copy = new MapMatchingResult(
                        member.getId(),
                        base.getMatchedLatitude(),
                        base.getMatchedLongitude(),
                        base.getConfidence()
                );
                copy.setMatchedRoadId(base.getMatchedRoadId());
                copy.setMatchedRoadName(base.getMatchedRoadName());
                copy.setMatchedRoad(base.getMatchedRoad());
                copy.setPosition(expanded.size());
                expanded.add(copy);
            }
        }
        return expanded;
    }

    private List<MapMatchingResult> matchSimpleSegment(TrajectorySegment segment, RoadNetwork roadNetwork) {
        List<List<CandidatePoint>> candidateLists = buildCandidateLists(segment.points, roadNetwork, SIMPLE_MAX_CANDIDATES);
        return runFirstOrderViterbi(segment.points, candidateLists, roadNetwork, segment.startWindowIndex);
    }

    private List<MapMatchingResult> matchComplexSegment(TrajectorySegment segment, RoadNetwork roadNetwork) {
        if (segment.points.size() < 3) {
            return matchSimpleSegment(segment, roadNetwork);
        }

        List<List<CandidatePoint>> candidateLists = buildCandidateLists(segment.points, roadNetwork, COMPLEX_MAX_CANDIDATES);
        if (segment.points.size() > MAX_SECOND_ORDER_SEGMENT_POINTS) {
            return runFirstOrderViterbi(segment.points, candidateLists, roadNetwork, segment.startWindowIndex);
        }
        return runSecondOrderViterbi(segment, candidateLists, roadNetwork);
    }

    private List<List<CandidatePoint>> buildCandidateLists(List<TrackPoint> points,
                                                           RoadNetwork roadNetwork,
                                                           int maxCandidates) {
        List<List<CandidatePoint>> result = new ArrayList<>(points.size());
        for (TrackPoint point : points) {
            result.add(buildCandidatesForPoint(point, roadNetwork, maxCandidates));
        }
        return result;
    }

    private List<CandidatePoint> buildCandidatesForPoint(TrackPoint point,
                                                         RoadNetwork roadNetwork,
                                                         int maxCandidates) {
        double lat = bytesToDoubleSafe(point.getLatEnc());
        double lon = bytesToDoubleSafe(point.getLngEnc());
        Double heading = headingOf(point);
        Map<Long, CandidatePoint> candidateMap = new LinkedHashMap<>();

        for (double radius : CANDIDATE_SEARCH_RADII_METERS) {
            List<RoadEdge> nearby = findNearbyEdgesCached(roadNetwork, lat, lon, radius);
            if (nearby.isEmpty()) {
                continue;
            }
            appendCandidatePoints(candidateMap, point, nearby, lat, lon, heading, 1.0, false);
        }

        if (candidateMap.isEmpty()) {
            for (double radius : CANDIDATE_FALLBACK_RADII_METERS) {
                List<RoadEdge> nearby = findNearbyEdgesCached(roadNetwork, lat, lon, radius);
                if (nearby.isEmpty()) {
                    continue;
                }
                appendCandidatePoints(candidateMap, point, nearby, lat, lon, heading,
                        EXTENDED_RADIUS_OBSERVATION_PENALTY, true);
            }
        }

        List<CandidatePoint> candidates = new ArrayList<>(candidateMap.values());
        rebalanceCandidatesForCorridorScene(candidates, heading);
        candidates.sort((a, b) -> {
            int byProb = Double.compare(b.observationProb, a.observationProb);
            if (byProb != 0) {
                return byProb;
            }
            return Double.compare(a.distanceMeters, b.distanceMeters);
        });

        if (candidates.size() > maxCandidates) {
            candidates = new ArrayList<>(candidates.subList(0, maxCandidates));
        }
        normalizeObservationProbabilities(candidates);
        return candidates;
    }

    private void appendCandidatePoints(Map<Long, CandidatePoint> target,
                                       TrackPoint point,
                                       List<RoadEdge> nearby,
                                       double lat,
                                       double lon,
                                       Double heading,
                                       double observationPenalty,
                                       boolean strictHeadingFilter) {
        for (RoadEdge road : nearby) {
            Projection projection = projectPointToRoad(lat, lon, road);
            double localDirection = projection.localDirectionDegrees;
            double theta = heading == null ? -1.0 : directionDifferenceToRoad(heading, road, localDirection);
            if (strictHeadingFilter && shouldSkipByHeading(theta, projection.distanceMeters)) {
                continue;
            }
            double observation = calculateObservationProbability(projection.distanceMeters, theta, road) * observationPenalty;
            CandidatePoint candidate = new CandidatePoint(point, road,
                    projection.lat, projection.lon,
                    projection.distanceMeters, theta,
                    observation, projection.offsetFromStartMeters, localDirection);
            mergeCandidatePoint(target, candidate);
        }
    }

    private void mergeCandidatePoint(Map<Long, CandidatePoint> target, CandidatePoint candidate) {
        if (candidate == null || candidate.getRoad() == null) {
            return;
        }
        long key = normalizeRoadId(candidate.getRoad());
        CandidatePoint existing = target.get(key);
        if (existing == null) {
            target.put(key, candidate);
            return;
        }
        boolean replace = candidate.getObservationProb() > existing.getObservationProb();
        if (!replace && Math.abs(candidate.getObservationProb() - existing.getObservationProb()) < 1e-12) {
            replace = candidate.getDistanceMeters() < existing.getDistanceMeters();
        }
        if (replace) {
            target.put(key, candidate);
        }
    }

    private void rebalanceCandidatesForCorridorScene(List<CandidatePoint> candidates, Double heading) {
        if (candidates == null || candidates.size() < 2) {
            return;
        }

        CandidatePoint corridorAnchor = findCorridorAnchorCandidate(candidates, heading);
        if (corridorAnchor == null) {
            return;
        }

        for (CandidatePoint candidate : candidates) {
            if (candidate == corridorAnchor || candidate.getRoad() == null) {
                continue;
            }
            double factor = 1.0;
            RoadEdge anchorRoad = corridorAnchor.getRoad();
            RoadEdge candidateRoad = candidate.getRoad();
            double roadTheta = roadToRoadDirectionDifference(corridorAnchor, candidate);
            boolean similarDirection = roadTheta <= 25.0;
            boolean shareNode = roadsShareNode(anchorRoad, candidateRoad);
            boolean sameWayRoad = sameWay(anchorRoad, candidateRoad);
            boolean sameRoadRoad = sameRoad(anchorRoad, candidateRoad);
            int layerDiff = Math.abs(anchorRoad.getLayerLevel() - candidateRoad.getLayerLevel());
            boolean structureDiff = anchorRoad.isBridge() != candidateRoad.isBridge()
                    || anchorRoad.isTunnel() != candidateRoad.isTunnel();

            if (sameRoadRoad || sameWayRoad) {
                factor *= 1.10;
            }

            if (similarDirection && isDirectLowClassSurfaceRoad(candidateRoad)) {
                factor *= shareNode ? 0.58 : 0.34;
            }

            if (similarDirection && (layerDiff > 0 || structureDiff)) {
                factor *= shareNode ? 0.72 : 0.52;
            }

            if (similarDirection && candidate.getDistanceMeters() > corridorAnchor.getDistanceMeters() + 10.0) {
                factor *= 0.82;
            }

            if (isHighSpeedCorridor(candidateRoad) && !isHighSpeedCorridor(anchorRoad) && candidate.getDistanceMeters() <= corridorAnchor.getDistanceMeters() + 8.0) {
                factor *= 1.05;
            }

            candidate.setObservationProb(Math.max(candidate.getObservationProb() * factor, MIN_PROB));
        }

        corridorAnchor.setObservationProb(Math.max(corridorAnchor.getObservationProb() * 1.12, MIN_PROB));
    }

    private CandidatePoint findCorridorAnchorCandidate(List<CandidatePoint> candidates, Double heading) {
        CandidatePoint best = null;
        double bestScore = NEG_INF;
        for (CandidatePoint candidate : candidates) {
            RoadEdge road = candidate.getRoad();
            if (road == null || !isHighSpeedCorridor(road)) {
                continue;
            }
            double theta = candidate.getThetaDegrees();
            boolean headingOk = theta < 0.0 || theta <= 35.0;
            if (!headingOk && heading != null) {
                continue;
            }
            if (candidate.getDistanceMeters() > 90.0) {
                continue;
            }
            double score = Math.log(Math.max(candidate.getObservationProb(), MIN_PROB))
                    - candidate.getDistanceMeters() / 80.0;
            if (road.hasStructureTag()) {
                score += 0.18;
            }
            if (theta >= 0.0) {
                score -= theta / 180.0;
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean shouldSkipByHeading(double thetaDegrees, double distanceMeters) {
        if (thetaDegrees < 0.0) {
            return false;
        }
        return distanceMeters > COMPLEXITY_RADIUS_METERS && thetaDegrees > EXTENDED_RADIUS_MAX_HEADING_DIFF;
    }

    private List<RoadEdge> findNearbyEdgesCached(RoadNetwork roadNetwork, double lat, double lon, double radius) {
        MatchingRuntimeContext context = currentMatchingContext();
        if (context == null) {
            return roadNetwork.findNearbyEdges(lat, lon, radius);
        }

        String key = buildNearbyEdgeCacheKey(lat, lon, radius);
        List<RoadEdge> cached = context.nearbyEdgeCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<RoadEdge> nearby = roadNetwork.findNearbyEdges(lat, lon, radius);
        context.nearbyEdgeCache.put(key, nearby);
        return nearby;
    }

    private String buildNearbyEdgeCacheKey(double lat, double lon, double radius) {
        int latBucket = (int) Math.round(lat * NEARBY_EDGE_CACHE_BUCKET);
        int lonBucket = (int) Math.round(lon * NEARBY_EDGE_CACHE_BUCKET);
        int radiusBucket = (int) Math.round(radius);
        return latBucket + ":" + lonBucket + ":" + radiusBucket;
    }

    private void normalizeObservationProbabilities(List<CandidatePoint> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        double sum = 0.0;
        for (CandidatePoint candidate : candidates) {
            sum += Math.max(candidate.observationProb, MIN_PROB);
        }
        if (sum <= 0.0) {
            double uniform = 1.0 / candidates.size();
            for (CandidatePoint candidate : candidates) {
                candidate.observationProb = uniform;
            }
            return;
        }
        for (CandidatePoint candidate : candidates) {
            candidate.observationProb = Math.max(candidate.observationProb, MIN_PROB) / sum;
        }
    }

    private List<MapMatchingResult> runFirstOrderViterbi(List<TrackPoint> points,
                                                         List<List<CandidatePoint>> candidateLists,
                                                         RoadNetwork roadNetwork,
                                                         int startPositionOffset) {
        int n = points.size();
        if (n == 0) {
            return new ArrayList<>();
        }
        if (hasEmptyCandidateLayer(candidateLists)) {
            return buildFallbackSegmentResults(points, candidateLists, startPositionOffset);
        }

        List<double[]> scores = new ArrayList<>(n);
        List<int[]> backPointers = new ArrayList<>(n);

        double[] firstScores = new double[candidateLists.get(0).size()];
        int[] firstBack = new int[candidateLists.get(0).size()];
        Arrays.fill(firstBack, -1);
        for (int j = 0; j < candidateLists.get(0).size(); j++) {
            firstScores[j] = Math.log(Math.max(candidateLists.get(0).get(j).observationProb, MIN_PROB));
        }
        scores.add(firstScores);
        backPointers.add(firstBack);

        for (int t = 1; t < n; t++) {
            List<CandidatePoint> prevCandidates = candidateLists.get(t - 1);
            List<CandidatePoint> currCandidates = candidateLists.get(t);
            double[] currScores = new double[currCandidates.size()];
            int[] currBack = new int[currCandidates.size()];
            Arrays.fill(currScores, NEG_INF);
            Arrays.fill(currBack, -1);

            double greatCircleDistance = calculateGreatCircleDistanceBetweenPoints(points.get(t - 1), points.get(t));

            for (int j = 0; j < currCandidates.size(); j++) {
                CandidatePoint curr = currCandidates.get(j);
                double obsLog = Math.log(Math.max(curr.observationProb, MIN_PROB));

                for (int i = 0; i < prevCandidates.size(); i++) {
                    double prevScore = scores.get(t - 1)[i];
                    if (prevScore <= NEG_INF / 2) {
                        continue;
                    }
                    CandidatePoint prev = prevCandidates.get(i);
                    double transition = calculateFirstOrderTransitionProbability(prev, curr, greatCircleDistance, roadNetwork);
                    double total = prevScore + Math.log(Math.max(transition, MIN_PROB)) + obsLog;
                    if (total > currScores[j]) {
                        currScores[j] = total;
                        currBack[j] = i;
                    }
                }
            }

            scores.add(currScores);
            backPointers.add(currBack);
        }

        int[] selected = new int[n];
        Arrays.fill(selected, -1);
        selected[n - 1] = argMax(scores.get(n - 1));
        if (selected[n - 1] < 0) {
            return buildFallbackSegmentResults(points, candidateLists, startPositionOffset);
        }
        for (int t = n - 1; t >= 1; t--) {
            selected[t - 1] = backPointers.get(t)[selected[t]];
            if (selected[t - 1] < 0) {
                selected[t - 1] = 0;
            }
        }

        return buildResultsFromSelection(points, candidateLists, selected, startPositionOffset);
    }

    private List<MapMatchingResult> runSecondOrderViterbi(TrajectorySegment segment,
                                                          List<List<CandidatePoint>> candidateLists,
                                                          RoadNetwork roadNetwork) {
        int n = segment.points.size();
        if (n < 3 || hasEmptyCandidateLayer(candidateLists)) {
            return runFirstOrderViterbi(segment.points, candidateLists, roadNetwork, segment.startWindowIndex);
        }

        List<CandidatePoint> c0 = candidateLists.get(0);
        List<CandidatePoint> c1 = candidateLists.get(1);
        Map<Integer, int[][]> backLayers = new HashMap<>();

        double[] initialProbs = calculateInitialProbabilities(c0);
        double gc01 = calculateGreatCircleDistanceBetweenPoints(segment.points.get(0), segment.points.get(1));

        double[][] prevLayer = new double[c0.size()][c1.size()];
        for (int i = 0; i < c0.size(); i++) {
            for (int j = 0; j < c1.size(); j++) {
                double pairObservation = calculateSecondOrderObservationProbability(c0.get(i), c1.get(j), gc01, roadNetwork);
                prevLayer[i][j] = Math.log(Math.max(initialProbs[i], MIN_PROB))
                        + Math.log(Math.max(pairObservation, MIN_PROB));
            }
        }

        for (int t = 2; t < n; t++) {
            List<CandidatePoint> prevPrevCandidates = candidateLists.get(t - 2);
            List<CandidatePoint> prevCandidates = candidateLists.get(t - 1);
            List<CandidatePoint> currCandidates = candidateLists.get(t);

            double[][] currLayer = new double[prevCandidates.size()][currCandidates.size()];
            int[][] currBack = new int[prevCandidates.size()][currCandidates.size()];
            for (int i = 0; i < prevCandidates.size(); i++) {
                Arrays.fill(currLayer[i], NEG_INF);
                Arrays.fill(currBack[i], -1);
            }

            double gcPrevCurr = calculateGreatCircleDistanceBetweenPoints(segment.points.get(t - 1), segment.points.get(t));
            double gcTotal = calculateGreatCircleDistanceBetweenPoints(segment.points.get(t - 2), segment.points.get(t - 1))
                    + gcPrevCurr;
            double localComplexity = calculateLocalComplexity(segment.complexities, t);

            for (int prevIdx = 0; prevIdx < prevCandidates.size(); prevIdx++) {
                for (int currIdx = 0; currIdx < currCandidates.size(); currIdx++) {
                    CandidatePoint prev = prevCandidates.get(prevIdx);
                    CandidatePoint curr = currCandidates.get(currIdx);
                    double pairObservation = calculateSecondOrderObservationProbability(prev, curr, gcPrevCurr, roadNetwork);

                    for (int prevPrevIdx = 0; prevPrevIdx < prevPrevCandidates.size(); prevPrevIdx++) {
                        double prevScore = prevLayer[prevPrevIdx][prevIdx];
                        if (prevScore <= NEG_INF / 2) {
                            continue;
                        }

                        CandidatePoint prevPrev = prevPrevCandidates.get(prevPrevIdx);
                        double secondTransition = calculateSecondOrderTransitionProbability(prevPrev, prev, curr, gcTotal, roadNetwork);
                        double adaptive = adaptiveCombine(pairObservation, secondTransition, localComplexity);
                        double total = prevScore + Math.log(Math.max(adaptive, MIN_PROB));
                        if (total > currLayer[prevIdx][currIdx]) {
                            currLayer[prevIdx][currIdx] = total;
                            currBack[prevIdx][currIdx] = prevPrevIdx;
                        }
                    }
                }
            }

            backLayers.put(t, currBack);
            prevLayer = currLayer;
        }

        int bestPrev = -1;
        int bestCurr = -1;
        double bestScore = NEG_INF;
        for (int i = 0; i < prevLayer.length; i++) {
            for (int j = 0; j < prevLayer[i].length; j++) {
                if (prevLayer[i][j] > bestScore) {
                    bestScore = prevLayer[i][j];
                    bestPrev = i;
                    bestCurr = j;
                }
            }
        }

        if (bestPrev < 0 || bestCurr < 0) {
            return runFirstOrderViterbi(segment.points, candidateLists, roadNetwork, segment.startWindowIndex);
        }

        int[] selected = new int[n];
        Arrays.fill(selected, -1);
        selected[n - 2] = bestPrev;
        selected[n - 1] = bestCurr;

        for (int t = n - 1; t >= 2; t--) {
            int[][] back = backLayers.get(t);
            if (back == null) {
                return runFirstOrderViterbi(segment.points, candidateLists, roadNetwork, segment.startWindowIndex);
            }
            selected[t - 2] = back[selected[t - 1]][selected[t]];
            if (selected[t - 2] < 0) {
                selected[t - 2] = 0;
            }
        }

        return buildResultsFromSelection(segment.points, candidateLists, selected, segment.startWindowIndex);
    }

    private List<MapMatchingResult> buildResultsFromSelection(List<TrackPoint> points,
                                                              List<List<CandidatePoint>> candidateLists,
                                                              int[] selected,
                                                              int startPositionOffset) {
        List<MapMatchingResult> results = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            CandidatePoint candidate = null;
            if (selected[i] >= 0 && selected[i] < candidateLists.get(i).size()) {
                candidate = candidateLists.get(i).get(selected[i]);
            } else if (!candidateLists.get(i).isEmpty()) {
                candidate = candidateLists.get(i).get(0);
            }

            MapMatchingResult result;
            if (candidate == null) {
                result = buildRawFallbackResult(points.get(i), startPositionOffset + i);
            } else {
                result = buildMatchResult(points.get(i), candidate, startPositionOffset + i);
            }
            results.add(result);
        }
        return results;
    }

    private List<MapMatchingResult> buildFallbackSegmentResults(List<TrackPoint> points,
                                                                List<List<CandidatePoint>> candidateLists,
                                                                int startPositionOffset) {
        List<MapMatchingResult> results = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            if (i < candidateLists.size() && !candidateLists.get(i).isEmpty()) {
                results.add(buildMatchResult(points.get(i), candidateLists.get(i).get(0), startPositionOffset + i));
            } else {
                results.add(buildRawFallbackResult(points.get(i), startPositionOffset + i));
            }
        }
        return results;
    }

    private boolean hasEmptyCandidateLayer(List<List<CandidatePoint>> candidateLists) {
        for (List<CandidatePoint> layer : candidateLists) {
            if (layer == null || layer.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private double[] calculateInitialProbabilities(List<CandidatePoint> firstCandidates) {
        double[] result = new double[firstCandidates.size()];
        double sum = 0.0;
        for (int i = 0; i < firstCandidates.size(); i++) {
            CandidatePoint candidate = firstCandidates.get(i);
            double thetaFactor = candidate.thetaDegrees < 0.0 ? 10.0 : Math.max(candidate.thetaDegrees, 5.0);
            result[i] = 1.0 / (Math.max(candidate.distanceMeters, 1.0) * thetaFactor);
            sum += result[i];
        }
        if (sum <= 0.0) {
            double uniform = firstCandidates.isEmpty() ? 0.0 : 1.0 / firstCandidates.size();
            Arrays.fill(result, uniform);
            return result;
        }
        for (int i = 0; i < result.length; i++) {
            result[i] /= sum;
        }
        return result;
    }

    private double calculateObservationProbability(double distanceMeters, double thetaDegrees, RoadEdge road) {
        double pDistance = gaussian(distanceMeters, 0.0, SIGMA_D_METERS);
        if (thetaDegrees < 0.0) {
            return Math.max(pDistance, MIN_PROB);
        }
        double pTheta = gaussian(thetaDegrees, 0.0, SIGMA_THETA_DEGREES);
        return Math.max(pDistance * pTheta, MIN_PROB);
    }

    private double directionDifferenceToRoad(double headingDegrees, RoadEdge road, double localDirectionDegrees) {
        double forward = calculateAngleDifference(headingDegrees, localDirectionDegrees);
        if (road == null || road.isOneWay()) {
            return forward;
        }
        double reverse = calculateAngleDifference(headingDegrees, (localDirectionDegrees + 180.0) % 360.0);
        return Math.min(forward, reverse);
    }

    private double roadToRoadDirectionDifference(CandidatePoint from, CandidatePoint to) {
        List<Double> fromDirections = candidateDirections(from);
        List<Double> toDirections = candidateDirections(to);
        double best = 180.0;
        for (Double fd : fromDirections) {
            for (Double td : toDirections) {
                best = Math.min(best, calculateAngleDifference(fd, td));
            }
        }
        return best;
    }

    private List<Double> candidateDirections(CandidatePoint candidate) {
        if (candidate == null) {
            return Collections.singletonList(0.0);
        }
        double local = candidate.roadDirection() == null ? 0.0 : candidate.roadDirection();
        if (candidate.road == null || candidate.road.isOneWay()) {
            return Collections.singletonList(local);
        }
        return Arrays.asList(local, (local + 180.0) % 360.0);
    }

    private double calculateFirstOrderTransitionProbability(CandidatePoint from,
                                                            CandidatePoint to,
                                                            double greatCircleDistance,
                                                            RoadNetwork roadNetwork) {
        double routeDistance = calculateRouteDistanceBetweenCandidates(from, to, roadNetwork);
        double dt = Math.abs(greatCircleDistance - routeDistance);
        double pDistance = Math.exp(-dt / ROUTE_BETA_METERS);

        boolean sameRoad = sameRoad(from.road, to.road);
        boolean sameWay = sameWay(from.road, to.road);
        boolean shareNode = roadsShareNode(from.road, to.road);

        double theta = roadToRoadDirectionDifference(from, to);
        double angleLambda = ANGLE_LAMBDA;
        if (sameWay || (shareNode && theta <= CURVE_CONTINUITY_ANGLE_DEGREES)) {
            angleLambda *= 0.55;
        }
        double pTheta = Math.exp(-angleLambda * (theta / 180.0));

        double continuityFactor = 1.0;
        if (sameRoad) {
            continuityFactor *= 1.15;
        } else if (sameWay) {
            continuityFactor *= 1.12;
        } else if (shareNode && theta <= CURVE_CONTINUITY_ANGLE_DEGREES) {
            continuityFactor *= CURVE_CONTINUITY_BONUS;
        }

        if (shareNode && areEdgesMutuallyConnected(from.road, to.road)) {
            continuityFactor *= 1.06;
        }

        if (!sameRoad && shareNode) {
            if (theta >= INTERSECTION_SHARP_TURN_DEGREES
                    && from.distanceMeters <= 18.0
                    && to.distanceMeters <= 18.0) {
                continuityFactor *= INTERSECTION_CROSSING_PENALTY;
            } else if (theta <= 20.0) {
                continuityFactor *= INTERSECTION_STRAIGHT_BONUS;
            }
        }

        if (!sameRoad
                && !sameWay
                && !shareNode
                && theta <= PARALLEL_ROAD_ANGLE_DEGREES
                && from.distanceMeters <= PARALLEL_ROAD_OBSERVATION_METERS
                && to.distanceMeters <= PARALLEL_ROAD_OBSERVATION_METERS) {
            double roadSeparation = estimateParallelRoadSeparation(from, to);
            if (roadSeparation >= PARALLEL_ROAD_SEPARATION_METERS) {
                continuityFactor *= PARALLEL_ROAD_SWITCH_PENALTY;
            }
        }

        continuityFactor *= sameRoadNameContinuityFactor(from, to);
        continuityFactor *= corridorClassPenalty(from, to, greatCircleDistance, theta);
        continuityFactor *= layerAwareTransitionFactor(from, to, greatCircleDistance);
        return Math.max(pDistance * pTheta * continuityFactor, MIN_PROB);
    }

    private double calculateSecondOrderObservationProbability(CandidatePoint prev,
                                                              CandidatePoint curr,
                                                              double greatCircleDistance,
                                                              RoadNetwork roadNetwork) {
        double firstOrderTransition = calculateFirstOrderTransitionProbability(prev, curr, greatCircleDistance, roadNetwork);
        return Math.max(prev.observationProb * curr.observationProb * firstOrderTransition, MIN_PROB);
    }

    private double calculateSecondOrderTransitionProbability(CandidatePoint p0,
                                                             CandidatePoint p1,
                                                             CandidatePoint p2,
                                                             double greatCircleTotal,
                                                             RoadNetwork roadNetwork) {
        double routeDistance = calculateRouteDistanceBetweenCandidates(p0, p1, roadNetwork)
                + calculateRouteDistanceBetweenCandidates(p1, p2, roadNetwork);
        double kt = Math.abs(greatCircleTotal - routeDistance);
        return Math.max(Math.exp(-kt / SECOND_ORDER_BETA_METERS), MIN_PROB);
    }

    private double adaptiveCombine(double observationProbability,
                                   double transitionProbability,
                                   double complexity) {
        double f = clamp01(complexity);
        double left = (1.0 - f) * Math.max(observationProbability, MIN_PROB);
        double right = f * Math.max(transitionProbability, MIN_PROB);
        double denominator = left + right;
        if (denominator <= 0.0) {
            return MIN_PROB;
        }
        return Math.max((left * right) / denominator, MIN_PROB);
    }

    private double calculateRouteDistanceBetweenCandidates(CandidatePoint from,
                                                           CandidatePoint to,
                                                           RoadNetwork roadNetwork) {
        if (from == null || to == null || from.road == null || to.road == null) {
            return 1_000_000.0;
        }

        MatchingRuntimeContext context = currentMatchingContext();
        String cacheKey = buildRouteDistanceCacheKey(from, to);
        if (context != null) {
            Double cached = context.routeDistanceCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        double distance;
        if (sameRoad(from.road, to.road)) {
            distance = calculateSameRoadRouteDistance(from.road, from.offsetFromStartMeters, to.offsetFromStartMeters, roadNetwork);
        } else {
            double direct = calculateGreatCircleDistance(from.projectedLat, from.projectedLon, to.projectedLat, to.projectedLon);
            NodePathChoice bestChoice = chooseBestNodePath(from.road, from.offsetFromStartMeters,
                    to.road, to.offsetFromStartMeters, roadNetwork);
            if (bestChoice == null) {
                if (likelySameCorridorContinuation(from, to, direct)) {
                    distance = corridorFallbackDistance(from, to, direct);
                } else if (direct <= LOCAL_DISCONNECT_HARD_BLOCK_METERS) {
                    distance = 1_000_000.0;
                } else {
                    distance = Math.max(direct * 8.0, 800.0);
                }
            } else {
                double best = Math.max(bestChoice.totalCostMeters, 0.0);
                if (likelySameCorridorContinuation(from, to, direct)
                        && best > Math.max(direct * 4.0, direct + 250.0)) {
                    distance = Math.min(best, corridorFallbackDistance(from, to, direct));
                } else {
                    distance = best;
                }
            }
        }

        if (context != null) {
            context.routeDistanceCache.put(cacheKey, distance);
        }
        return distance;
    }

    private String buildRouteDistanceCacheKey(CandidatePoint from, CandidatePoint to) {
        long fromRoadId = normalizeRoadId(from.road);
        long toRoadId = normalizeRoadId(to.road);
        long fromOffsetBucket = Math.round(from.offsetFromStartMeters / OFFSET_CACHE_BUCKET_METERS);
        long toOffsetBucket = Math.round(to.offsetFromStartMeters / OFFSET_CACHE_BUCKET_METERS);
        return fromRoadId + ":" + fromOffsetBucket + ":" + toRoadId + ":" + toOffsetBucket;
    }

    private long normalizeRoadId(RoadEdge road) {
        if (road == null || road.getId() == null) {
            return -1L;
        }
        return road.getId() % 1_000_000L;
    }

    private boolean sameRoad(RoadEdge a, RoadEdge b) {
        if (a == null || b == null || a.getId() == null || b.getId() == null) {
            return false;
        }
        return normalizeRoadId(a) == normalizeRoadId(b);
    }

    private boolean sameWay(RoadEdge a, RoadEdge b) {
        if (a == null || b == null || a.getSourceWayId() == null || b.getSourceWayId() == null) {
            return false;
        }
        return a.getSourceWayId().equals(b.getSourceWayId());
    }

    private boolean roadsShareNode(RoadEdge a, RoadEdge b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getStartNodeId() == b.getStartNodeId()
                || a.getStartNodeId() == b.getEndNodeId()
                || a.getEndNodeId() == b.getStartNodeId()
                || a.getEndNodeId() == b.getEndNodeId();
    }

    private double estimateParallelRoadSeparation(CandidatePoint from, CandidatePoint to) {
        if (from == null || to == null || from.road == null || to.road == null) {
            return 0.0;
        }
        try {
            RoadEdge.Projection p1 = from.road.project(to.projectedLat, to.projectedLon);
            RoadEdge.Projection p2 = to.road.project(from.projectedLat, from.projectedLon);
            return Math.max(p1.getDistanceMeters(), p2.getDistanceMeters());
        } catch (Throwable ignore) {
            return calculateGreatCircleDistance(from.projectedLat, from.projectedLon, to.projectedLat, to.projectedLon);
        }
    }

    private double calculateSameRoadRouteDistance(RoadEdge road,
                                                  double fromOffset,
                                                  double toOffset,
                                                  RoadNetwork roadNetwork) {
        double length = Math.max(road.getLengthM(), 1.0);
        double normalizedFrom = Math.max(0.0, Math.min(length, fromOffset));
        double normalizedTo = Math.max(0.0, Math.min(length, toOffset));
        double diff = normalizedTo - normalizedFrom;
        if (!road.isOneWay()) {
            return Math.abs(diff);
        }
        if (diff >= 0.0) {
            return diff;
        }

        NodePathChoice loopChoice = chooseBestNodePath(road, normalizedFrom, road, normalizedTo, roadNetwork);
        if (loopChoice != null) {
            return loopChoice.totalCostMeters;
        }
        return length + Math.abs(diff) + 500.0;
    }

    private NodePathChoice chooseBestNodePath(RoadEdge fromRoad,
                                              double fromOffset,
                                              RoadEdge toRoad,
                                              double toOffset,
                                              RoadNetwork roadNetwork) {
        if (fromRoad == null || toRoad == null || roadNetwork == null) {
            return null;
        }

        List<NodeAccessOption> startExits = buildExitOptions(fromRoad, fromOffset);
        List<NodeAccessOption> endEntries = buildEntryOptions(toRoad, toOffset);
        NodePathChoice best = null;
        NodePathChoice directChoice = chooseBestDirectHandoff(fromRoad, toRoad, startExits, endEntries, roadNetwork);

        for (NodeAccessOption startExit : startExits) {
            for (NodeAccessOption endEntry : endEntries) {
                List<Long> nodePath;
                double middleDistance;
                if (startExit.nodeId == endEntry.nodeId) {
                    nodePath = Collections.singletonList(startExit.nodeId);
                    middleDistance = 0.0;
                } else {
                    nodePath = roadNetwork.findNodePath(startExit.nodeId, endEntry.nodeId);
                    if (nodePath == null || nodePath.isEmpty()) {
                        continue;
                    }
                    middleDistance = calculateNodePathDistance(nodePath, roadNetwork);
                }

                double total = startExit.costMeters + middleDistance + endEntry.costMeters;
                if (best == null || total < best.totalCostMeters) {
                    best = new NodePathChoice(startExit, endEntry, nodePath, total);
                }
            }
        }
        if (directChoice != null && (best == null || directChoice.totalCostMeters < best.totalCostMeters)) {
            best = directChoice;
        }
        return best;
    }

    private List<NodeAccessOption> buildExitOptions(RoadEdge road, double offsetFromStartMeters) {
        double length = Math.max(road.getLengthM(), 1.0);
        double offset = Math.max(0.0, Math.min(length, offsetFromStartMeters));
        List<NodeAccessOption> options = new ArrayList<>(2);
        if (road.isOneWay()) {
            options.add(new NodeAccessOption(road.getEndNodeId(), Math.max(length - offset, 0.0)));
            return options;
        }
        options.add(new NodeAccessOption(road.getStartNodeId(), offset));
        options.add(new NodeAccessOption(road.getEndNodeId(), Math.max(length - offset, 0.0)));
        return options;
    }

    private List<NodeAccessOption> buildEntryOptions(RoadEdge road, double offsetFromStartMeters) {
        double length = Math.max(road.getLengthM(), 1.0);
        double offset = Math.max(0.0, Math.min(length, offsetFromStartMeters));
        List<NodeAccessOption> options = new ArrayList<>(2);
        if (road.isOneWay()) {
            options.add(new NodeAccessOption(road.getStartNodeId(), offset));
            return options;
        }
        options.add(new NodeAccessOption(road.getStartNodeId(), offset));
        options.add(new NodeAccessOption(road.getEndNodeId(), Math.max(length - offset, 0.0)));
        return options;
    }

    private double calculateNodePathDistance(List<Long> nodePath, RoadNetwork roadNetwork) {
        if (nodePath == null || nodePath.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < nodePath.size() - 1; i++) {
            total += findRoadEdgeLengthBetweenNodes(nodePath.get(i), nodePath.get(i + 1), roadNetwork);
        }
        return total;
    }

    private double calculateNodeDistance(long fromNodeId, long toNodeId, RoadNetwork roadNetwork) {
        RoadNode fromNode = roadNetwork.getNode(fromNodeId);
        RoadNode toNode = roadNetwork.getNode(toNodeId);
        if (fromNode == null || toNode == null) {
            return 0.0;
        }
        return calculateGreatCircleDistance(fromNode.getLat(), fromNode.getLon(), toNode.getLat(), toNode.getLon());
    }

    private boolean areEdgesMutuallyConnected(RoadEdge fromRoad, RoadEdge toRoad) {
        if (fromRoad == null || toRoad == null) {
            return false;
        }
        long fromId = normalizeRoadId(fromRoad);
        long toId = normalizeRoadId(toRoad);
        return containsConnectedEdgeId(fromRoad.getConnectedEdgeIds(), toId)
                || containsConnectedEdgeId(toRoad.getConnectedEdgeIds(), fromId);
    }

    private boolean containsConnectedEdgeId(Set<Long> connectedEdgeIds, long roadId) {
        if (connectedEdgeIds == null || connectedEdgeIds.isEmpty()) {
            return false;
        }
        for (Long id : connectedEdgeIds) {
            if (id != null && (id.longValue() % 1_000_000L) == roadId) {
                return true;
            }
        }
        return false;
    }

    private double estimateNodeHandoffGap(long fromNodeId, long toNodeId, RoadNetwork roadNetwork) {
        if (fromNodeId == toNodeId) {
            return 0.0;
        }
        RoadNode fromNode = roadNetwork.getNode(fromNodeId);
        RoadNode toNode = roadNetwork.getNode(toNodeId);
        if (fromNode == null || toNode == null) {
            return Double.POSITIVE_INFINITY;
        }
        return calculateGreatCircleDistance(fromNode.getLat(), fromNode.getLon(), toNode.getLat(), toNode.getLon());
    }

    private NodePathChoice chooseBestDirectHandoff(RoadEdge fromRoad,
                                                   RoadEdge toRoad,
                                                   List<NodeAccessOption> startExits,
                                                   List<NodeAccessOption> endEntries,
                                                   RoadNetwork roadNetwork) {
        if (fromRoad == null || toRoad == null || roadNetwork == null) {
            return null;
        }
        boolean sameWayRoad = sameWay(fromRoad, toRoad);
        boolean sharedNode = roadsShareNode(fromRoad, toRoad);
        boolean connected = areEdgesMutuallyConnected(fromRoad, toRoad);
        boolean strongCorridor = isStrongCorridorContinuation(fromRoad, toRoad);
        if (!sameWayRoad && !sharedNode && !connected && !strongCorridor) {
            return null;
        }

        double maxGap = sameWayRoad || strongCorridor ? SAME_WAY_NODE_HANDOFF_MAX_METERS : DIRECT_NODE_HANDOFF_MAX_METERS;
        NodePathChoice best = null;
        for (NodeAccessOption startExit : startExits) {
            for (NodeAccessOption endEntry : endEntries) {
                double gap = estimateNodeHandoffGap(startExit.nodeId, endEntry.nodeId, roadNetwork);
                if (gap > maxGap) {
                    continue;
                }
                double total = startExit.costMeters + gap + endEntry.costMeters;
                if (strongCorridor && gap <= SAME_WAY_NODE_HANDOFF_MAX_METERS) {
                    total *= 0.92;
                }
                List<Long> nodePath = startExit.nodeId == endEntry.nodeId
                        ? Collections.singletonList(startExit.nodeId)
                        : Arrays.asList(startExit.nodeId, endEntry.nodeId);
                if (best == null || total < best.totalCostMeters) {
                    best = new NodePathChoice(startExit, endEntry, nodePath, total);
                }
            }
        }
        return best;
    }

    private boolean isStrongCorridorContinuation(RoadEdge fromRoad, RoadEdge toRoad) {
        if (fromRoad == null || toRoad == null) {
            return false;
        }
        if (sameWay(fromRoad, toRoad) || areEdgesMutuallyConnected(fromRoad, toRoad)) {
            return true;
        }
        String fromName = normalizeRoadName(fromRoad.getName());
        String toName = normalizeRoadName(toRoad.getName());
        if (!fromName.isEmpty()
                && fromName.equals(toName)
                && isHighSpeedCorridor(fromRoad)
                && isHighSpeedCorridor(toRoad)) {
            return true;
        }
        double angle = calculateAngleDifference(safeDirection(fromRoad), safeDirection(toRoad));
        return angle <= SAME_CORRIDOR_FALLBACK_MAX_ANGLE;
    }

    private double estimateRoadEndpointGap(RoadEdge fromRoad, RoadEdge toRoad, RoadNetwork roadNetwork) {
        if (fromRoad == null || toRoad == null) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        best = Math.min(best, estimateNodeHandoffGap(fromRoad.getStartNodeId(), toRoad.getStartNodeId(), roadNetwork));
        best = Math.min(best, estimateNodeHandoffGap(fromRoad.getStartNodeId(), toRoad.getEndNodeId(), roadNetwork));
        best = Math.min(best, estimateNodeHandoffGap(fromRoad.getEndNodeId(), toRoad.getStartNodeId(), roadNetwork));
        best = Math.min(best, estimateNodeHandoffGap(fromRoad.getEndNodeId(), toRoad.getEndNodeId(), roadNetwork));
        return best;
    }

    private double findRoadEdgeLengthBetweenNodes(long fromNodeId, long toNodeId, RoadNetwork roadNetwork) {
        RoadEdge edge = findRoadEdgeBetweenNodes(fromNodeId, toNodeId, roadNetwork);
        if (edge != null) {
            return Math.max(edge.getLengthM(), 0.0);
        }
        return calculateNodeDistance(fromNodeId, toNodeId, roadNetwork);
    }

    private RoadEdge findRoadEdgeBetweenNodes(long fromNodeId, long toNodeId, RoadNetwork roadNetwork) {
        if (roadNetwork == null) {
            return null;
        }
        for (RoadEdge edge : roadNetwork.getEdges()) {
            if (edge.getStartNodeId() == fromNodeId && edge.getEndNodeId() == toNodeId) {
                return edge;
            }
            if (!edge.isOneWay() && edge.getStartNodeId() == toNodeId && edge.getEndNodeId() == fromNodeId) {
                return edge;
            }
        }
        return null;
    }

    private boolean likelySameCorridorContinuation(CandidatePoint from, CandidatePoint to, double directDistance) {
        if (from == null || to == null || from.getRoad() == null || to.getRoad() == null) {
            return false;
        }
        if (directDistance > SAME_CORRIDOR_FALLBACK_MAX_DIRECT_METERS) {
            return false;
        }
        if (isStrongCorridorContinuation(from.getRoad(), to.getRoad())) {
            return true;
        }
        String fromName = normalizeRoadName(from.getRoad().getName());
        String toName = normalizeRoadName(to.getRoad().getName());
        if (fromName.isEmpty() || !fromName.equals(toName)) {
            return false;
        }
        if (!isHighSpeedCorridor(from.getRoad()) || !isHighSpeedCorridor(to.getRoad())) {
            return false;
        }
        double angle = roadToRoadDirectionDifference(from, to);
        return angle <= SAME_CORRIDOR_FALLBACK_MAX_ANGLE;
    }

    private double corridorFallbackDistance(CandidatePoint from, CandidatePoint to, double directDistance) {
        double offsetGap = Math.abs(to.getOffsetFromStartMeters() - from.getOffsetFromStartMeters());
        return Math.max(directDistance, Math.min(directDistance * 1.25 + 8.0, offsetGap + 12.0));
    }

    private double sameRoadNameContinuityFactor(CandidatePoint from, CandidatePoint to) {
        if (from == null || to == null || from.getRoad() == null || to.getRoad() == null) {
            return 1.0;
        }
        RoadEdge fromRoad = from.getRoad();
        RoadEdge toRoad = to.getRoad();
        String fromName = normalizeRoadName(fromRoad.getName());
        String toName = normalizeRoadName(toRoad.getName());
        if (!fromName.isEmpty() && fromName.equals(toName)
                && isHighSpeedCorridor(fromRoad)
                && isHighSpeedCorridor(toRoad)) {
            return SAME_NAMED_CORRIDOR_BONUS;
        }
        if (isHighSpeedCorridor(fromRoad)
                && isDirectLowClassSurfaceRoad(toRoad)
                && toName.isEmpty()) {
            double angle = roadToRoadDirectionDifference(from, to);
            return angle <= 70.0 ? UNNAMED_SURFACE_EXIT_PENALTY : 0.72;
        }
        return 1.0;
    }

    private double corridorClassPenalty(CandidatePoint from,
                                        CandidatePoint to,
                                        double greatCircleDistance,
                                        double theta) {
        if (from == null || to == null || from.getRoad() == null || to.getRoad() == null) {
            return 1.0;
        }

        RoadEdge fromRoad = from.getRoad();
        RoadEdge toRoad = to.getRoad();
        boolean fromHigh = isHighSpeedCorridor(fromRoad);
        boolean toHigh = isHighSpeedCorridor(toRoad);
        boolean fromLow = isDirectLowClassSurfaceRoad(fromRoad);
        boolean toLow = isDirectLowClassSurfaceRoad(toRoad);
        boolean shareNode = roadsShareNode(fromRoad, toRoad);

        if (sameRoad(fromRoad, toRoad) || sameWay(fromRoad, toRoad)) {
            return 1.0;
        }

        double factor = 1.0;

        if (fromHigh && toLow) {
            boolean parallelLike = theta <= 25.0;
            boolean weakTurnEvidence = theta <= 38.0;
            if (!shareNode) {
                factor *= 0.10;
            } else if (parallelLike) {
                factor *= 0.16;
            } else if (weakTurnEvidence && greatCircleDistance <= 80.0) {
                factor *= 0.28;
            }
            if (to.getDistanceMeters() > from.getDistanceMeters() * 1.35) {
                factor *= 0.75;
            }
        }

        if (fromLow && toHigh) {
            if (!shareNode && theta <= 25.0) {
                factor *= 0.35;
            }
        }

        int layerDiff = Math.abs(fromRoad.getLayerLevel() - toRoad.getLayerLevel());
        boolean structureDiff = fromRoad.isBridge() != toRoad.isBridge()
                || fromRoad.isTunnel() != toRoad.isTunnel();
        if ((layerDiff > 0 || structureDiff)
                && theta <= 25.0
                && greatCircleDistance <= 80.0) {
            factor *= 0.30;
        }

        return Math.max(Math.min(factor, 1.0), MIN_PROB);
    }


    private String normalizeRoadName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || "未命名道路".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private boolean isHighSpeedCorridor(RoadEdge road) {
        if (road == null || road.getType() == null) {
            return false;
        }
        String type = road.getType();
        return road.isRampLike()
                || "motorway".equals(type)
                || "motorway_link".equals(type)
                || "trunk".equals(type)
                || "trunk_link".equals(type)
                || "primary".equals(type)
                || "primary_link".equals(type);
    }

    private boolean isExitConnectorRoad(RoadEdge road) {
        if (road == null || road.getType() == null) {
            return false;
        }
        String type = road.getType();
        return road.isRampLike() || type.endsWith("_link");
    }

    private boolean isDirectLowClassSurfaceRoad(RoadEdge road) {
        return isLowClassRoad(road) && !isExitConnectorRoad(road);
    }

    private boolean isLowClassRoad(RoadEdge road) {
        if (road == null || road.getType() == null) {
            return false;
        }
        String type = road.getType();
        return "secondary".equals(type)
                || "secondary_link".equals(type)
                || "tertiary".equals(type)
                || "tertiary_link".equals(type)
                || "unclassified".equals(type)
                || "residential".equals(type)
                || "service".equals(type)
                || "living_street".equals(type);
    }

    private double layerAwareTransitionFactor(CandidatePoint from, CandidatePoint to, double greatCircleDistance) {
        if (from == null || to == null || from.road == null || to.road == null) {
            return 1.0;
        }
        RoadEdge fromRoad = from.road;
        RoadEdge toRoad = to.road;
        int layerDiff = Math.abs(fromRoad.getLayerLevel() - toRoad.getLayerLevel());
        boolean structureDiff = fromRoad.isBridge() != toRoad.isBridge()
                || fromRoad.isTunnel() != toRoad.isTunnel();
        boolean rampTransition = fromRoad.isRampLike() || toRoad.isRampLike();

        double factor = 1.0;
        if (layerDiff == 0 && !structureDiff) {
            factor *= LAYER_STRUCTURE_KEEP_BONUS;
        }
        if ((layerDiff > 0 || structureDiff)
                && !rampTransition
                && greatCircleDistance <= LOCAL_DISCONNECT_HARD_BLOCK_METERS) {
            factor *= LAYER_STRUCTURE_SWITCH_PENALTY;
        }
        if (rampTransition && roadsShareNode(fromRoad, toRoad)) {
            boolean bothCorridor = isHighSpeedCorridor(fromRoad) && isHighSpeedCorridor(toRoad);
            boolean directSurfaceExit = (isHighSpeedCorridor(fromRoad) && isDirectLowClassSurfaceRoad(toRoad))
                    || (isHighSpeedCorridor(toRoad) && isDirectLowClassSurfaceRoad(fromRoad));
            if (bothCorridor) {
                factor *= RAMP_TRANSITION_BONUS;
            } else if (directSurfaceExit) {
                factor *= 0.78;
            }
        }
        return factor;
    }

    private List<GeoPoint> nodePathToPoints(List<Long> nodePath, RoadNetwork roadNetwork) {
        List<GeoPoint> points = new ArrayList<>();
        if (nodePath == null || nodePath.isEmpty()) {
            return points;
        }
        for (Long nodeId : nodePath) {
            GeoPoint point = nodeToPoint(roadNetwork, nodeId);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private GeoPoint nodeToPoint(RoadNetwork roadNetwork, long nodeId) {
        RoadNode node = roadNetwork.getNode(nodeId);
        if (node == null) {
            return null;
        }
        return new GeoPoint(node.getLat(), node.getLon());
    }

    private void appendPointIfFar(List<GeoPoint> target, GeoPoint point) {
        if (target == null || point == null) {
            return;
        }
        if (target.isEmpty()) {
            target.add(point);
            return;
        }
        GeoPoint last = target.get(target.size() - 1);
        if (calculateGreatCircleDistance(last.lat, last.lon, point.lat, point.lon) >= 2.0) {
            target.add(point);
        }
    }

    private List<PointComplexity> calculateComplexities(List<TrackPoint> points, RoadNetwork roadNetwork) {
        List<PointComplexity> result = new ArrayList<>(points.size());
        for (TrackPoint point : points) {
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            List<RoadEdge> nearby = findNearbyEdgesCached(roadNetwork, lat, lon, COMPLEXITY_RADIUS_METERS);
            double directional = calculateDirectionalComplexity(nearby);
            double connectivity = calculateConnectivityComplexity(nearby);
            double complexity = Math.max(directional, connectivity);
            boolean isComplex = complexity >= OVERALL_COMPLEX_THRESHOLD
                    || (directional >= DIRECTION_COMPLEX_THRESHOLD && connectivity >= 0.35)
                    || containsHighDegreeNode(nearby, COMPLEX_NODE_DEGREE_THRESHOLD);
            result.add(new PointComplexity(directional, connectivity, complexity, isComplex));
        }
        return result;
    }

    private double calculateDirectionalComplexity(List<RoadEdge> roads) {
        if (roads == null || roads.size() < 2) {
            return 0.0;
        }

        List<Double> directionDiffs = new ArrayList<>();
        for (int i = 0; i < roads.size(); i++) {
            for (int j = i + 1; j < roads.size(); j++) {
                directionDiffs.add(calculateAngleDifference(safeDirection(roads.get(i)), safeDirection(roads.get(j))));
            }
        }

        if (directionDiffs.isEmpty()) {
            return 0.0;
        }

        double mean = 0.0;
        for (Double diff : directionDiffs) {
            mean += diff;
        }
        mean /= directionDiffs.size();

        double variance = 0.0;
        for (Double diff : directionDiffs) {
            variance += Math.pow(diff - mean, 2);
        }
        variance /= directionDiffs.size();

        double meanScore = clamp01(mean / 90.0);
        double varianceScore = clamp01(Math.sqrt(variance) / 90.0);
        return clamp01(0.75 * meanScore + 0.25 * varianceScore);
    }

    private double calculateConnectivityComplexity(List<RoadEdge> roads) {
        if (roads == null || roads.isEmpty()) {
            return 0.0;
        }
        double degreeAvg = 0.0;
        for (RoadEdge road : roads) {
            degreeAvg += safeNodeDegree(road);
        }
        degreeAvg /= roads.size();
        return clamp01((degreeAvg - 2.0) / 3.0);
    }

    private boolean containsHighDegreeNode(List<RoadEdge> roads, int threshold) {
        for (RoadEdge road : roads) {
            if (safeNodeDegree(road) >= threshold) {
                return true;
            }
        }
        return false;
    }

    private List<TrajectorySegment> segmentTrajectory(List<TrackPoint> representativePoints,
                                                      List<PointComplexity> complexities) {
        int n = representativePoints.size();
        if (n == 0) {
            return new ArrayList<>();
        }

        boolean[] complexFlags = new boolean[n];
        for (int i = 0; i < n; i++) {
            complexFlags[i] = complexities.get(i).complex;
        }

        boolean[] expanded = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!complexFlags[i]) {
                continue;
            }
            int from = Math.max(0, i - COMPLEX_CONTEXT_POINTS);
            int to = Math.min(n - 1, i + COMPLEX_CONTEXT_POINTS);
            for (int j = from; j <= to; j++) {
                expanded[j] = true;
            }
        }

        List<TrajectorySegment> segments = new ArrayList<>();
        int start = 0;
        while (start < n) {
            boolean complex = expanded[start];
            int end = start;
            while (end + 1 < n && expanded[end + 1] == complex) {
                end++;
            }

            List<TrackPoint> segmentPoints = new ArrayList<>(representativePoints.subList(start, end + 1));
            List<Double> segmentComplexities = new ArrayList<>();
            double complexitySum = 0.0;
            for (int i = start; i <= end; i++) {
                double value = complexities.get(i).overall;
                segmentComplexities.add(value);
                complexitySum += value;
            }

            segments.add(new TrajectorySegment(
                    segmentPoints,
                    complex,
                    start,
                    end,
                    complexitySum / Math.max(segmentPoints.size(), 1),
                    segmentComplexities
            ));
            start = end + 1;
        }

        return segments;
    }

    private double calculateLocalComplexity(List<Double> complexities, int centerIndex) {
        int from = Math.max(0, centerIndex - 1);
        int to = Math.min(complexities.size() - 1, centerIndex);
        double sum = 0.0;
        for (int i = from; i <= to; i++) {
            sum += complexities.get(i);
        }
        return clamp01(sum / Math.max(to - from + 1, 1));
    }

    private List<CompressedWindow> compressStayWindows(List<TrackPoint> points) {
        if (points.isEmpty()) {
            return new ArrayList<>();
        }

        List<CompressedWindow> windows = new ArrayList<>();
        int i = 0;
        while (i < points.size()) {
            TrackPoint anchor = points.get(i);
            double anchorLat = bytesToDoubleSafe(anchor.getLatEnc());
            double anchorLon = bytesToDoubleSafe(anchor.getLngEnc());

            int end = i;
            while (end + 1 < points.size()) {
                TrackPoint next = points.get(end + 1);
                double nextLat = bytesToDoubleSafe(next.getLatEnc());
                double nextLon = bytesToDoubleSafe(next.getLngEnc());
                double distance = calculateGreatCircleDistance(anchorLat, anchorLon, nextLat, nextLon);
                if (distance > STAY_CLUSTER_RADIUS_METERS) {
                    break;
                }
                end++;
            }

            if (end > i) {
                TrackPoint first = points.get(i);
                TrackPoint last = points.get(end);
                long duration = Math.max(0L, safeTs(last) - safeTs(first));
                double avgSpeed = averageSpeed(points.subList(i, end + 1));
                if (duration >= STAY_CLUSTER_MIN_DURATION_MS && avgSpeed <= STAY_CLUSTER_MAX_SPEED_MPS) {
                    List<TrackPoint> members = new ArrayList<>(points.subList(i, end + 1));
                    TrackPoint representative = members.get(members.size() / 2);
                    windows.add(new CompressedWindow(i, end, representative, members));
                    i = end + 1;
                    continue;
                }
            }

            windows.add(new CompressedWindow(i, i, points.get(i), Collections.singletonList(points.get(i))));
            i++;
        }

        return windows;
    }

    private double averageSpeed(List<TrackPoint> points) {
        if (points.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (TrackPoint point : points) {
            Float speed = point.getSpeedMps();
            if (speed != null) {
                sum += speed;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    @Override
    public List<Map<String, Object>> generateMockTrackPoints(Long tripId) {
        List<Map<String, Object>> points = new ArrayList<>();
        double[][] waypoints = {
                {39.984702, 116.318417},
                {39.984683, 116.321006},
                {39.984686, 116.324650},
                {39.982927, 116.326152},
                {39.980956, 116.326317},
                {39.979395, 116.326491},
                {39.978083, 116.326550},
                {39.976696, 116.326358}
        };

        long ts = System.currentTimeMillis() - 30 * 60 * 1000L;
        Random random = new Random(42L);
        for (int i = 0; i < waypoints.length - 1; i++) {
            double[] from = waypoints[i];
            double[] to = waypoints[i + 1];
            double heading = bearingDegrees(from[0], from[1], to[0], to[1]);
            for (int j = 0; j < 6; j++) {
                double ratio = j / 6.0;
                double lat = from[0] + (to[0] - from[0]) * ratio;
                double lng = from[1] + (to[1] - from[1]) * ratio;
                Map<String, Object> point = new HashMap<>();
                point.put("lat", lat + (random.nextDouble() - 0.5) * 0.00003);
                point.put("lng", lng + (random.nextDouble() - 0.5) * 0.00003);
                point.put("ts", ts);
                point.put("heading", heading);
                point.put("speed", 11.0 + random.nextDouble() * 4.0);
                points.add(point);
                ts += 10_000L;
            }
        }
        return points;
    }

    @Override
    public Map<String, TrackPolylineVO> processTrackPoints(Long tripId, List<Map<String, Object>> originalPoints) {
        return withMatchingContext(() -> {
            Map<String, TrackPolylineVO> result = new HashMap<>();
            if (originalPoints == null || originalPoints.isEmpty()) {
                TrackPolylineVO empty = TrackPolylineVO.builder()
                        .points(new ArrayList<>())
                        .distanceM(0L)
                        .simplified(false)
                        .build();
                result.put("rawPolyline", empty);
                result.put("matchedPolyline", empty);
                result.put("reconstructedPolyline", empty);
                return result;
            }

            List<TrackPoint> trackPoints = buildTrackPointsFromRawInput(tripId, originalPoints);
            List<GeoPointVO> rawGeoPoints = convertTrackPointsToGeoPoints(trackPoints);
            if (trackPoints.size() < 2) {
                TrackPolylineVO raw = buildPolyline(rawGeoPoints, false);
                result.put("rawPolyline", raw);
                result.put("matchedPolyline", raw);
                result.put("reconstructedPolyline", raw);
                return result;
            }

            List<TrackPoint> prepared = preprocessGeneralTrack(trackPoints);
            if (prepared.size() < 2) {
                TrackPolylineVO raw = buildPolyline(rawGeoPoints, false);
                result.put("rawPolyline", raw);
                result.put("matchedPolyline", raw);
                result.put("reconstructedPolyline", raw);
                return result;
            }

            RoadNetwork roadNetwork = loadRoadNetwork(prepared);
            List<MotionSegment> motionSegments = classifyMotionSegments(prepared, roadNetwork);
            ProcessedPolylineBundle bundle = processMotionSegments(motionSegments, roadNetwork);

            List<GeoPointVO> matchedPoints = bundle.matchedPoints.isEmpty() ? rawGeoPoints : bundle.matchedPoints;
            List<GeoPointVO> reconstructedPoints = bundle.reconstructedPoints.isEmpty() ? matchedPoints : bundle.reconstructedPoints;

            result.put("rawPolyline", buildPolyline(rawGeoPoints, false));
            result.put("matchedPolyline", buildPolyline(matchedPoints, false, bundle.matchedSegments));
            result.put("reconstructedPolyline", buildPolyline(reconstructedPoints, bundle.reconstructedSimplified, bundle.reconstructedSegments));
            return result;
        });
    }

    private List<TrackPoint> preprocessGeneralTrack(List<TrackPoint> rawPoints) {
        if (rawPoints == null || rawPoints.isEmpty()) {
            return new ArrayList<>();
        }

        List<TrackPoint> sorted = sortByTimestamp(rawPoints);
        List<TrackPoint> valid = new ArrayList<>();
        for (TrackPoint point : sorted) {
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            if (!isValidCoordinate(lat, lon)) {
                continue;
            }
            Float accuracy = point.getAccuracyM();
            if (accuracy != null && accuracy > GENERAL_MAX_ACCURACY_METERS) {
                continue;
            }
            valid.add(copyTrackPoint(point));
        }

        if (valid.size() < 2) {
            return valid;
        }

        fillHeadingAndSpeed(valid);
        valid = removeTemporalDuplicates(valid);
        valid = removeExtremeJumpPoints(valid);
        fillHeadingAndSpeed(valid);
        return valid;
    }

    private List<TrackPoint> removeExtremeJumpPoints(List<TrackPoint> points) {
        if (points.size() < 3) {
            return points;
        }

        List<TrackPoint> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            TrackPoint prev = result.get(result.size() - 1);
            TrackPoint curr = points.get(i);
            TrackPoint next = points.get(i + 1);

            double dPrevCurr = calculateGreatCircleDistanceBetweenPoints(prev, curr);
            double dCurrNext = calculateGreatCircleDistanceBetweenPoints(curr, next);
            double dPrevNext = calculateGreatCircleDistanceBetweenPoints(prev, next);
            double sPrevCurr = speedFromDistanceAndTime(dPrevCurr, safeTs(curr) - safeTs(prev));
            double sCurrNext = speedFromDistanceAndTime(dCurrNext, safeTs(next) - safeTs(curr));

            boolean isolatedJump = sPrevCurr > EXTREME_JUMP_SPEED_MPS
                    && sCurrNext > EXTREME_JUMP_SPEED_MPS
                    && dPrevNext < Math.max(20.0, 0.4 * (dPrevCurr + dCurrNext));
            if (isolatedJump) {
                continue;
            }
            result.add(curr);
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    private List<MotionSegment> classifyMotionSegments(List<TrackPoint> points, RoadNetwork roadNetwork) {
        if (points == null || points.isEmpty()) {
            return new ArrayList<>();
        }

        List<PointMotionEvidence> evidence = buildMotionEvidence(points, roadNetwork);
        List<MotionMode> initialModes = new ArrayList<>(evidence.size());
        MotionMode previous = MotionMode.WALKING;
        for (PointMotionEvidence ev : evidence) {
            MotionMode decided = decideMotionMode(ev, previous);
            initialModes.add(decided);
            previous = decided;
        }

        List<MotionMode> smoothedModes = smoothMotionModes(initialModes, evidence, points);
        return buildMotionSegments(points, evidence, smoothedModes);
    }

    private List<PointMotionEvidence> buildMotionEvidence(List<TrackPoint> points, RoadNetwork roadNetwork) {
        List<Double> roadDistances = new ArrayList<>(points.size());
        List<Double> effectiveSpeeds = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            TrackPoint point = points.get(i);
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            roadDistances.add(nearestRoadDistanceMeters(lat, lon, roadNetwork));
            effectiveSpeeds.add(effectiveSpeed(points, i));
        }

        List<PointMotionEvidence> result = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            int from = Math.max(0, i - MOTION_CLASSIFY_WINDOW_RADIUS);
            int to = Math.min(points.size() - 1, i + MOTION_CLASSIFY_WINDOW_RADIUS);

            List<Double> speedWindow = new ArrayList<>();
            double maxSpeed = 0.0;
            int nearRoad = 0;
            int offRoad = 0;
            int stayCount = 0;
            for (int j = from; j <= to; j++) {
                double speed = effectiveSpeeds.get(j);
                speedWindow.add(speed);
                maxSpeed = Math.max(maxSpeed, speed);
                double roadDistance = roadDistances.get(j);
                if (roadDistance <= WALKING_NEAR_ROAD_METERS) {
                    nearRoad++;
                }
                if (roadDistance >= WALKING_OFF_ROAD_METERS) {
                    offRoad++;
                }
                if (speed <= 1.0) {
                    stayCount++;
                }
            }

            double medianSpeed = median(speedWindow);
            double windowDistance = calculateGreatCircleDistanceBetweenPoints(points.get(from), points.get(to));
            long windowDurationMs = Math.max(1L, safeTs(points.get(to)) - safeTs(points.get(from)));
            double windowAvgSpeed = speedFromDistanceAndTime(windowDistance, windowDurationMs);
            double nearRoadRatio = nearRoad / (double) (to - from + 1);
            double offRoadRatio = offRoad / (double) (to - from + 1);
            double stayRatio = stayCount / (double) (to - from + 1);

            double driveScore = 0.0;
            if (medianSpeed >= DRIVE_CLASSIFY_MEDIAN_SPEED_MPS) {
                driveScore += 1.2;
            }
            if (maxSpeed >= DRIVE_CLASSIFY_STRONG_SPEED_MPS) {
                driveScore += 1.4;
            }
            if (windowAvgSpeed >= 4.2) {
                driveScore += 0.8;
            }
            if (nearRoadRatio >= 0.60) {
                driveScore += 0.8;
            }
            if (nearRoadRatio >= 0.80 && medianSpeed >= 2.8) {
                driveScore += 0.8;
            }

            double walkScore = 0.0;
            if (medianSpeed <= WALK_CLASSIFY_STRONG_SPEED_MPS) {
                walkScore += 1.2;
            }
            if (maxSpeed <= WALKING_MAX_REASONABLE_SPEED_MPS) {
                walkScore += 0.5;
            }
            if (offRoadRatio >= 0.40) {
                walkScore += 1.0;
            }
            if (stayRatio >= 0.50) {
                walkScore += 0.7;
            }
            if (windowAvgSpeed <= 2.0) {
                walkScore += 0.6;
            }

            result.add(new PointMotionEvidence(
                    points.get(i),
                    roadDistances.get(i),
                    medianSpeed,
                    maxSpeed,
                    windowAvgSpeed,
                    nearRoadRatio,
                    offRoadRatio,
                    stayRatio,
                    driveScore,
                    walkScore
            ));
        }
        return result;
    }

    private MotionMode decideMotionMode(PointMotionEvidence evidence, MotionMode previousMode) {
        if (evidence.maxSpeed >= 9.0 && evidence.nearRoadRatio >= 0.4) {
            return MotionMode.DRIVING;
        }
        if (evidence.driveScore >= evidence.walkScore + 0.7) {
            return MotionMode.DRIVING;
        }
        if (evidence.walkScore >= evidence.driveScore + 0.4) {
            return MotionMode.WALKING;
        }
        if (previousMode == MotionMode.DRIVING
                && evidence.nearRoadRatio >= 0.55
                && evidence.medianSpeed >= 2.8) {
            return MotionMode.DRIVING;
        }
        if (previousMode == MotionMode.WALKING
                && (evidence.medianSpeed <= 2.6 || evidence.offRoadRatio >= 0.35 || evidence.stayRatio >= 0.45)) {
            return MotionMode.WALKING;
        }
        return evidence.nearRoadRatio >= 0.75 && evidence.medianSpeed >= 3.2
                ? MotionMode.DRIVING
                : MotionMode.WALKING;
    }

    private List<MotionMode> smoothMotionModes(List<MotionMode> modes,
                                               List<PointMotionEvidence> evidence,
                                               List<TrackPoint> points) {
        if (modes.isEmpty()) {
            return modes;
        }

        List<MotionMode> smoothed = new ArrayList<>(modes);
        boolean changed;
        int passes = 0;
        do {
            changed = false;
            List<MotionSegment> segments = buildMotionSegments(points, evidence, smoothed);
            for (MotionSegment segment : segments) {
                if (segment.points.isEmpty()) {
                    continue;
                }
                long duration = safeTs(segment.points.get(segment.points.size() - 1))
                        - safeTs(segment.points.get(0));
                boolean shortSegment = segment.points.size() < MIN_MODE_SEGMENT_POINTS
                        || duration < MIN_MODE_SEGMENT_DURATION_MS;
                if (!shortSegment) {
                    continue;
                }

                MotionMode replacement = chooseReplacementMode(segment, segments);
                if (replacement != null && replacement != segment.mode) {
                    for (int i = segment.startIndex; i <= segment.endIndex; i++) {
                        smoothed.set(i, replacement);
                    }
                    changed = true;
                }
            }
            passes++;
        } while (changed && passes < 3);

        return smoothed;
    }

    private MotionMode chooseReplacementMode(MotionSegment segment, List<MotionSegment> segments) {
        int idx = segments.indexOf(segment);
        MotionSegment prev = idx > 0 ? segments.get(idx - 1) : null;
        MotionSegment next = idx < segments.size() - 1 ? segments.get(idx + 1) : null;

        if (prev != null && next != null && prev.mode == next.mode) {
            return prev.mode;
        }

        if (segment.mode == MotionMode.WALKING
                && segment.nearRoadRatio >= 0.75
                && segment.avgSpeed >= 3.5
                && (prev != null && prev.mode == MotionMode.DRIVING || next != null && next.mode == MotionMode.DRIVING)) {
            return MotionMode.DRIVING;
        }

        if (segment.mode == MotionMode.DRIVING
                && (segment.avgSpeed <= 3.0 || segment.offRoadRatio >= 0.45)
                && (prev != null && prev.mode == MotionMode.WALKING || next != null && next.mode == MotionMode.WALKING)) {
            return MotionMode.WALKING;
        }

        if (prev == null && next != null) {
            return next.mode;
        }
        if (next == null && prev != null) {
            return prev.mode;
        }
        if (prev != null && next != null) {
            return prev.points.size() >= next.points.size() ? prev.mode : next.mode;
        }
        return null;
    }

    private List<MotionSegment> buildMotionSegments(List<TrackPoint> points,
                                                    List<PointMotionEvidence> evidence,
                                                    List<MotionMode> modes) {
        List<MotionSegment> segments = new ArrayList<>();
        if (points.isEmpty()) {
            return segments;
        }

        int start = 0;
        while (start < points.size()) {
            MotionMode mode = modes.get(start);
            int end = start;
            while (end + 1 < points.size() && modes.get(end + 1) == mode) {
                end++;
            }

            List<TrackPoint> segmentPoints = new ArrayList<>(points.subList(start, end + 1));
            double sumRoadRatio = 0.0;
            double sumOffRoadRatio = 0.0;
            double sumWindowSpeed = 0.0;
            double maxSpeed = 0.0;
            for (int i = start; i <= end; i++) {
                PointMotionEvidence ev = evidence.get(i);
                sumRoadRatio += ev.nearRoadRatio;
                sumOffRoadRatio += ev.offRoadRatio;
                sumWindowSpeed += ev.windowAvgSpeed;
                maxSpeed = Math.max(maxSpeed, ev.maxSpeed);
            }
            int count = end - start + 1;
            segments.add(new MotionSegment(
                    mode,
                    segmentPoints,
                    start,
                    end,
                    sumRoadRatio / count,
                    sumOffRoadRatio / count,
                    sumWindowSpeed / count,
                    maxSpeed
            ));
            start = end + 1;
        }
        return segments;
    }

    private ProcessedPolylineBundle processMotionSegments(List<MotionSegment> motionSegments, RoadNetwork roadNetwork) {
        List<GeoPointVO> matchedPoints = new ArrayList<>();
        List<GeoPointVO> reconstructedPoints = new ArrayList<>();
        List<TrackPolylineVO.SegmentVO> matchedSegments = new ArrayList<>();
        List<TrackPolylineVO.SegmentVO> reconstructedSegments = new ArrayList<>();
        boolean reconstructedSimplified = false;

        for (MotionSegment segment : motionSegments) {
            SegmentPolylineResult segmentResult = segment.mode == MotionMode.DRIVING
                    ? processDrivingSegment(segment, roadNetwork)
                    : processWalkingSegment(segment);
            appendGeoPoints(matchedPoints, segmentResult.matchedPoints);
            appendGeoPoints(reconstructedPoints, segmentResult.reconstructedPoints);

            TrackPolylineVO.SegmentVO matchedSegment = buildSegmentPolyline(
                    segmentResult.mode,
                    segmentResult.matchedPoints,
                    false,
                    true
            );
            if (matchedSegment != null) {
                matchedSegments.add(matchedSegment);
            }

            TrackPolylineVO.SegmentVO reconstructedSegment = buildSegmentPolyline(
                    segmentResult.mode,
                    segmentResult.reconstructedPoints,
                    segmentResult.reconstructedSimplified,
                    false
            );
            if (reconstructedSegment != null) {
                reconstructedSegments.add(reconstructedSegment);
            }

            reconstructedSimplified = reconstructedSimplified || segmentResult.reconstructedSimplified;
        }

        return new ProcessedPolylineBundle(
                matchedPoints,
                reconstructedPoints,
                reconstructedSimplified,
                matchedSegments,
                reconstructedSegments
        );
    }

    private SegmentPolylineResult processDrivingSegment(MotionSegment segment, RoadNetwork roadNetwork) {
        List<TrackPoint> prepared = preprocessDrivingTrack(segment.points);
        if (prepared.size() < 2) {
            List<GeoPointVO> raw = convertTrackPointsToGeoPoints(segment.points);
            return new SegmentPolylineResult(segment.mode, raw, raw, false);
        }

        List<MapMatchingResult> matchedResults = matchTrajectory(prepared, roadNetwork);
        List<GeoPointVO> matchedPoints = convertMatchedResultsToGeoPoints(matchedResults, prepared);
        List<GeoPointVO> reconstructedPoints = reconstructPathWithRoadNetwork(matchedResults, prepared, roadNetwork);
        if (matchedPoints.isEmpty()) {
            matchedPoints = convertTrackPointsToGeoPoints(prepared);
        }
        if (reconstructedPoints.isEmpty()) {
            reconstructedPoints = matchedPoints;
        }
        return new SegmentPolylineResult(segment.mode, matchedPoints, reconstructedPoints, false);
    }

    private SegmentPolylineResult processWalkingSegment(MotionSegment segment) {
        List<TrackPoint> prepared = preprocessWalkingTrack(segment.points);
        List<GeoPointVO> walkingPoints = convertTrackPointsToGeoPoints(prepared.isEmpty() ? segment.points : prepared);
        if (walkingPoints.isEmpty()) {
            walkingPoints = convertTrackPointsToGeoPoints(segment.points);
        }
        return new SegmentPolylineResult(segment.mode, walkingPoints, walkingPoints, true);
    }

    private List<TrackPoint> preprocessWalkingTrack(List<TrackPoint> rawPoints) {
        if (rawPoints == null || rawPoints.isEmpty()) {
            return new ArrayList<>();
        }

        List<TrackPoint> sorted = sortByTimestamp(rawPoints);
        List<TrackPoint> valid = new ArrayList<>();
        for (TrackPoint point : sorted) {
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            if (!isValidCoordinate(lat, lon)) {
                continue;
            }
            Float accuracy = point.getAccuracyM();
            if (accuracy != null && accuracy > WALKING_MAX_ACCURACY_METERS) {
                continue;
            }
            valid.add(copyTrackPoint(point));
        }

        if (valid.size() < 2) {
            return valid;
        }

        fillHeadingAndSpeed(valid);
        valid = removeTemporalDuplicates(valid);
        valid = removeWalkingSpikePoints(valid);
        fillHeadingAndSpeed(valid);
        valid = smoothWalkingCoordinates(valid);
        valid = compressWalkingStayClusters(valid);
        valid = simplifyWalkingTrack(valid);
        fillHeadingAndSpeed(valid);
        return valid;
    }

    private List<TrackPoint> removeWalkingSpikePoints(List<TrackPoint> points) {
        if (points.size() < 3) {
            return points;
        }

        List<TrackPoint> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            TrackPoint prev = result.get(result.size() - 1);
            TrackPoint curr = points.get(i);
            TrackPoint next = points.get(i + 1);

            double dPrevCurr = calculateGreatCircleDistanceBetweenPoints(prev, curr);
            double dCurrNext = calculateGreatCircleDistanceBetweenPoints(curr, next);
            double dPrevNext = calculateGreatCircleDistanceBetweenPoints(prev, next);
            double sPrevCurr = speedFromDistanceAndTime(dPrevCurr, safeTs(curr) - safeTs(prev));
            double sCurrNext = speedFromDistanceAndTime(dCurrNext, safeTs(next) - safeTs(curr));
            double accuracy = curr.getAccuracyM() == null ? 0.0 : curr.getAccuracyM();

            boolean obviousSpike = sPrevCurr > WALKING_SPIKE_SPEED_MPS
                    && sCurrNext > WALKING_SPIKE_SPEED_MPS
                    && dPrevNext < Math.max(12.0, 0.45 * (dPrevCurr + dCurrNext));
            boolean accuracyInducedSpike = accuracy > 60.0
                    && dPrevCurr > 35.0
                    && dCurrNext > 35.0
                    && dPrevNext < 15.0;
            if (obviousSpike || accuracyInducedSpike) {
                continue;
            }
            result.add(curr);
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    private List<TrackPoint> smoothWalkingCoordinates(List<TrackPoint> points) {
        if (points.size() < 3) {
            return points;
        }

        List<TrackPoint> smoothed = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            if (i == 0 || i == points.size() - 1) {
                smoothed.add(copyTrackPoint(points.get(i)));
                continue;
            }

            int from = Math.max(0, i - WALKING_SMOOTH_RADIUS);
            int to = Math.min(points.size() - 1, i + WALKING_SMOOTH_RADIUS);
            double latSum = 0.0;
            double lonSum = 0.0;
            double weightSum = 0.0;
            for (int j = from; j <= to; j++) {
                TrackPoint neighbor = points.get(j);
                double accuracy = neighbor.getAccuracyM() == null ? 15.0 : neighbor.getAccuracyM();
                double weight = 1.0 / (1.0 + Math.abs(i - j)) * 1.0 / (1.0 + accuracy / 30.0);
                latSum += bytesToDoubleSafe(neighbor.getLatEnc()) * weight;
                lonSum += bytesToDoubleSafe(neighbor.getLngEnc()) * weight;
                weightSum += weight;
            }

            TrackPoint copy = copyTrackPoint(points.get(i));
            copy.setLatEnc(doubleToBytes(latSum / Math.max(weightSum, 1.0)));
            copy.setLngEnc(doubleToBytes(lonSum / Math.max(weightSum, 1.0)));
            smoothed.add(copy);
        }
        return smoothed;
    }

    private List<TrackPoint> compressWalkingStayClusters(List<TrackPoint> points) {
        if (points.isEmpty()) {
            return points;
        }

        List<TrackPoint> compressed = new ArrayList<>();
        int i = 0;
        while (i < points.size()) {
            TrackPoint anchor = points.get(i);
            double anchorLat = bytesToDoubleSafe(anchor.getLatEnc());
            double anchorLon = bytesToDoubleSafe(anchor.getLngEnc());

            int end = i;
            while (end + 1 < points.size()) {
                TrackPoint next = points.get(end + 1);
                double nextLat = bytesToDoubleSafe(next.getLatEnc());
                double nextLon = bytesToDoubleSafe(next.getLngEnc());
                double distance = calculateGreatCircleDistance(anchorLat, anchorLon, nextLat, nextLon);
                if (distance > WALKING_STAY_RADIUS_METERS) {
                    break;
                }
                end++;
            }

            if (end > i) {
                TrackPoint first = points.get(i);
                TrackPoint last = points.get(end);
                long duration = Math.max(0L, safeTs(last) - safeTs(first));
                double avgSpeed = averageSpeed(points.subList(i, end + 1));
                if (duration >= WALKING_STAY_MIN_DURATION_MS && avgSpeed <= WALKING_STAY_MAX_SPEED_MPS) {
                    compressed.add(buildCentroidTrackPoint(points.subList(i, end + 1)));
                    i = end + 1;
                    continue;
                }
            }

            compressed.add(copyTrackPoint(points.get(i)));
            i++;
        }
        return compressed;
    }

    private TrackPoint buildCentroidTrackPoint(List<TrackPoint> cluster) {
        TrackPoint base = copyTrackPoint(cluster.get(cluster.size() / 2));
        double latSum = 0.0;
        double lonSum = 0.0;
        double accSum = 0.0;
        int accCount = 0;
        for (TrackPoint point : cluster) {
            latSum += bytesToDoubleSafe(point.getLatEnc());
            lonSum += bytesToDoubleSafe(point.getLngEnc());
            if (point.getAccuracyM() != null) {
                accSum += point.getAccuracyM();
                accCount++;
            }
        }
        base.setLatEnc(doubleToBytes(latSum / cluster.size()));
        base.setLngEnc(doubleToBytes(lonSum / cluster.size()));
        if (accCount > 0) {
            base.setAccuracyM((float) (accSum / accCount));
        }
        return base;
    }

    private List<TrackPoint> simplifyWalkingTrack(List<TrackPoint> points) {
        if (points.size() < 3) {
            return points;
        }

        List<TrackPoint> simplified = new ArrayList<>();
        simplified.add(copyTrackPoint(points.get(0)));
        for (int i = 1; i < points.size() - 1; i++) {
            TrackPoint lastKept = simplified.get(simplified.size() - 1);
            TrackPoint curr = points.get(i);
            TrackPoint next = points.get(i + 1);
            double moveDistance = calculateGreatCircleDistanceBetweenPoints(lastKept, curr);
            long timeGap = Math.max(0L, safeTs(curr) - safeTs(lastKept));
            double turnAngle = calculateTurnAngle(lastKept, curr, next);
            if (moveDistance >= WALKING_SIMPLIFY_MIN_MOVE_METERS
                    || timeGap >= WALKING_SIMPLIFY_MAX_IDLE_GAP_MS
                    || turnAngle >= 28.0) {
                simplified.add(copyTrackPoint(curr));
            }
        }
        simplified.add(copyTrackPoint(points.get(points.size() - 1)));
        return simplified;
    }

    private double calculateTurnAngle(TrackPoint a, TrackPoint b, TrackPoint c) {
        double ab = bearingDegrees(bytesToDoubleSafe(a.getLatEnc()), bytesToDoubleSafe(a.getLngEnc()),
                bytesToDoubleSafe(b.getLatEnc()), bytesToDoubleSafe(b.getLngEnc()));
        double bc = bearingDegrees(bytesToDoubleSafe(b.getLatEnc()), bytesToDoubleSafe(b.getLngEnc()),
                bytesToDoubleSafe(c.getLatEnc()), bytesToDoubleSafe(c.getLngEnc()));
        return calculateAngleDifference(ab, bc);
    }

    private double nearestRoadDistanceMeters(double lat, double lon, RoadNetwork roadNetwork) {
        if (roadNetwork == null || roadNetwork.getEdgeCount() == 0) {
            return Double.POSITIVE_INFINITY;
        }

        List<RoadEdge> nearby = findNearbyEdgesCached(roadNetwork, lat, lon, 60.0);
        if (nearby.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }

        double min = Double.POSITIVE_INFINITY;
        for (RoadEdge road : nearby) {
            Projection projection = projectPointToRoad(lat, lon, road);
            min = Math.min(min, projection.distanceMeters);
        }
        return min;
    }

    private double effectiveSpeed(List<TrackPoint> points, int index) {
        TrackPoint point = points.get(index);
        if (point.getSpeedMps() != null && point.getSpeedMps() >= 0.0f) {
            return point.getSpeedMps();
        }
        return inferSpeed(points, index);
    }

    private double speedFromDistanceAndTime(double distanceMeters, long deltaMs) {
        if (deltaMs <= 0L) {
            return 0.0;
        }
        return distanceMeters / (deltaMs / 1000.0);
    }

    private double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    private void appendGeoPoints(List<GeoPointVO> target, List<GeoPointVO> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (GeoPointVO point : source) {
            if (point == null || point.getLat() == null || point.getLng() == null) {
                continue;
            }
            if (target.isEmpty()) {
                target.add(point);
                continue;
            }
            GeoPointVO last = target.get(target.size() - 1);
            if (last.getLat() != null && last.getLng() != null) {
                double distance = calculateGreatCircleDistance(last.getLat(), last.getLng(), point.getLat(), point.getLng());
                Long lastTs = last.getTs();
                Long currTs = point.getTs();
                boolean sameTs = lastTs == null ? currTs == null : lastTs.equals(currTs);
                if (distance < 1.5 && sameTs) {
                    continue;
                }
            }
            target.add(point);
        }
    }

    private TrackPolylineVO buildPolyline(List<GeoPointVO> points, boolean simplified) {
        return buildPolyline(points, simplified, null);
    }

    private TrackPolylineVO buildPolyline(List<GeoPointVO> points,
                                          boolean simplified,
                                          List<TrackPolylineVO.SegmentVO> segments) {
        return TrackPolylineVO.builder()
                .points(points)
                .distanceM(calculateTotalDistance(points))
                .simplified(simplified)
                .segments(segments)
                .build();
    }

    private TrackPolylineVO.SegmentVO buildSegmentPolyline(MotionMode mode,
                                                           List<GeoPointVO> points,
                                                           boolean simplified,
                                                           boolean matchedLayer) {
        if (points == null || points.size() < 2) {
            return null;
        }
        return TrackPolylineVO.SegmentVO.builder()
                .mode(mode == null ? MODE_UNKNOWN : mode.name())
                .color(resolveSegmentColor(mode, matchedLayer))
                .width(matchedLayer ? 4 : 6)
                .dottedLine(false)
                .simplified(simplified)
                .distanceM(calculateTotalDistance(points))
                .points(points)
                .build();
    }

    private String resolveSegmentColor(MotionMode mode, boolean matchedLayer) {
        if (mode == MotionMode.WALKING) {
            return matchedLayer ? MATCHED_WALKING_COLOR : RECONSTRUCTED_WALKING_COLOR;
        }
        if (mode == MotionMode.DRIVING) {
            return matchedLayer ? MATCHED_DRIVING_COLOR : RECONSTRUCTED_DRIVING_COLOR;
        }
        return matchedLayer ? MATCHED_DRIVING_COLOR : RECONSTRUCTED_DRIVING_COLOR;
    }

    private List<TrackPoint> buildTrackPointsFromRawInput(Long tripId, List<Map<String, Object>> originalPoints) {
        List<TrackPoint> points = new ArrayList<>();
        for (Map<String, Object> map : originalPoints) {
            Double lat = getDoubleValueNullable(map, "lat");
            Double lng = getDoubleValueNullable(map, "lng");
            if (lat == null || lng == null) {
                continue;
            }
            CoordType inputCoordType = resolveInputCoordType(map);
            double[] normalized = toInternalWgs84(lat, lng, inputCoordType);

            TrackPoint point = new TrackPoint();
            point.setTripId(tripId);
            point.setUserId(null);
            point.setRawCoordType(INTERNAL_COORD_TYPE);
            point.setSource(TrackPointSource.WX_FG);
            point.setAccuracyM(getFloatValue(map, "accuracy", 10.0f));
            point.setLatEnc(doubleToBytes(normalized[0]));
            point.setLngEnc(doubleToBytes(normalized[1]));
            point.setTs(getLongValue(map, "ts", System.currentTimeMillis()));
            if (map.get("heading") instanceof Number) {
                point.setHeadingDeg(((Number) map.get("heading")).floatValue());
            }
            if (map.get("speed") instanceof Number) {
                point.setSpeedMps(((Number) map.get("speed")).floatValue());
            }
            points.add(point);
        }
        return sortByTimestamp(points);
    }

    private List<TrackPoint> preprocessDrivingTrack(List<TrackPoint> rawPoints) {
        if (rawPoints == null || rawPoints.isEmpty()) {
            return new ArrayList<>();
        }

        List<TrackPoint> sorted = sortByTimestamp(rawPoints);
        List<TrackPoint> valid = new ArrayList<>();
        for (TrackPoint point : sorted) {
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            if (!isValidCoordinate(lat, lon)) {
                continue;
            }
            Float accuracy = point.getAccuracyM();
            if (accuracy != null && accuracy > MAX_ACCURACY_METERS) {
                continue;
            }
            valid.add(copyTrackPoint(point));
        }

        if (valid.size() < 2) {
            return valid;
        }

        fillHeadingAndSpeed(valid);
        valid = removeTemporalDuplicates(valid);
        valid = removeSpikePoints(valid);
        fillHeadingAndSpeed(valid);
        return valid;
    }

    private List<TrackPoint> removeTemporalDuplicates(List<TrackPoint> points) {
        if (points.size() < 2) {
            return points;
        }
        List<TrackPoint> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            TrackPoint prev = result.get(result.size() - 1);
            TrackPoint curr = points.get(i);
            long dt = safeTs(curr) - safeTs(prev);
            double distance = calculateGreatCircleDistanceBetweenPoints(prev, curr);
            if (dt < MIN_TIME_DELTA_MS && distance < 2.0) {
                continue;
            }
            result.add(curr);
        }
        return result;
    }

    private List<TrackPoint> removeSpikePoints(List<TrackPoint> points) {
        if (points.size() < 3) {
            return points;
        }

        List<TrackPoint> result = new ArrayList<>();
        result.add(points.get(0));
        for (int i = 1; i < points.size() - 1; i++) {
            TrackPoint prev = result.get(result.size() - 1);
            TrackPoint curr = points.get(i);
            TrackPoint next = points.get(i + 1);

            double dPrevCurr = calculateGreatCircleDistanceBetweenPoints(prev, curr);
            double dCurrNext = calculateGreatCircleDistanceBetweenPoints(curr, next);
            double dPrevNext = calculateGreatCircleDistanceBetweenPoints(prev, next);
            long dtPrevCurr = Math.max(1L, safeTs(curr) - safeTs(prev));
            long dtCurrNext = Math.max(1L, safeTs(next) - safeTs(curr));

            double speedPrevCurr = dPrevCurr / (dtPrevCurr / 1000.0);
            double speedCurrNext = dCurrNext / (dtCurrNext / 1000.0);

            boolean impossibleStep = dPrevCurr > MAX_STEP_DISTANCE_METERS || dCurrNext > MAX_STEP_DISTANCE_METERS
                    || speedPrevCurr > MAX_DRIVING_SPEED_MPS || speedCurrNext > MAX_DRIVING_SPEED_MPS;
            boolean bridgeLooksReasonable = dPrevNext <= MAX_STEP_DISTANCE_METERS;

            if (impossibleStep && bridgeLooksReasonable) {
                continue;
            }
            result.add(curr);
        }
        result.add(points.get(points.size() - 1));
        return result;
    }

    private void fillHeadingAndSpeed(List<TrackPoint> points) {
        for (int i = 0; i < points.size(); i++) {
            TrackPoint point = points.get(i);
            Float heading = point.getHeadingDeg();
            if (heading == null || heading < 0.0f) {
                Double inferred = inferHeading(points, i);
                if (inferred != null) {
                    point.setHeadingDeg(inferred.floatValue());
                }
            }
            Float speed = point.getSpeedMps();
            if (speed == null || speed < 0.0f) {
                Float inferred = inferSpeed(points, i);
                if (inferred != null) {
                    point.setSpeedMps(inferred);
                }
            }
        }
    }

    private Double inferHeading(List<TrackPoint> points, int index) {
        TrackPoint curr = points.get(index);
        if (index > 0) {
            TrackPoint prev = points.get(index - 1);
            double distance = calculateGreatCircleDistanceBetweenPoints(prev, curr);
            if (distance >= 3.0) {
                return bearingDegrees(bytesToDoubleSafe(prev.getLatEnc()), bytesToDoubleSafe(prev.getLngEnc()),
                        bytesToDoubleSafe(curr.getLatEnc()), bytesToDoubleSafe(curr.getLngEnc()));
            }
        }
        if (index + 1 < points.size()) {
            TrackPoint next = points.get(index + 1);
            double distance = calculateGreatCircleDistanceBetweenPoints(curr, next);
            if (distance >= 3.0) {
                return bearingDegrees(bytesToDoubleSafe(curr.getLatEnc()), bytesToDoubleSafe(curr.getLngEnc()),
                        bytesToDoubleSafe(next.getLatEnc()), bytesToDoubleSafe(next.getLngEnc()));
            }
        }
        return null;
    }

    private Float inferSpeed(List<TrackPoint> points, int index) {
        if (index == 0) {
            if (points.size() < 2) {
                return 0.0f;
            }
            TrackPoint curr = points.get(0);
            TrackPoint next = points.get(1);
            long dt = Math.max(1L, safeTs(next) - safeTs(curr));
            return (float) (calculateGreatCircleDistanceBetweenPoints(curr, next) / (dt / 1000.0));
        }

        TrackPoint prev = points.get(index - 1);
        TrackPoint curr = points.get(index);
        long dt = Math.max(1L, safeTs(curr) - safeTs(prev));
        return (float) (calculateGreatCircleDistanceBetweenPoints(prev, curr) / (dt / 1000.0));
    }

    private List<TrackPoint> sortByTimestamp(List<TrackPoint> trackPoints) {
        List<TrackPoint> sorted = new ArrayList<>(trackPoints);
        sorted.sort(Comparator.comparingLong(this::safeTs));
        return sorted;
    }

    private TrackPoint copyTrackPoint(TrackPoint source) {
        TrackPoint copy = new TrackPoint();
        copy.setId(source.getId());
        copy.setTripId(source.getTripId());
        copy.setUserId(source.getUserId());
        copy.setTs(source.getTs());
        copy.setLatEnc(source.getLatEnc() == null ? null : source.getLatEnc().clone());
        copy.setLngEnc(source.getLngEnc() == null ? null : source.getLngEnc().clone());
        copy.setAccuracyM(source.getAccuracyM());
        copy.setSpeedMps(source.getSpeedMps());
        copy.setHeadingDeg(source.getHeadingDeg());
        copy.setSource(source.getSource());
        copy.setRawCoordType(source.getRawCoordType());
        return copy;
    }

    private long safeTs(TrackPoint point) {
        if (point == null) {
            return 0L;
        }
        Long ts = point.getTs();
        return ts == null ? 0L : ts;
    }

    private Double headingOf(TrackPoint point) {
        if (point == null) {
            return null;
        }
        Float heading = point.getHeadingDeg();
        return heading == null ? null : (double) heading;
    }

    private float getFloatValue(Map<String, Object> map, String key, float defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return defaultValue;
    }

    private long getLongValue(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    private Double getDoubleValueNullable(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private List<TrackPoint> normalizeTrackPointsToInternalWgs84(List<TrackPoint> points) {
        if (points == null) {
            return new ArrayList<>();
        }
        List<TrackPoint> normalized = new ArrayList<>(points.size());
        for (TrackPoint point : points) {
            normalized.add(normalizeTrackPointToInternalWgs84(point));
        }
        return normalized;
    }

    private TrackPoint normalizeTrackPointToInternalWgs84(TrackPoint source) {
        if (source == null) {
            return null;
        }
        TrackPoint copy = copyTrackPoint(source);
        double lat = bytesToDoubleSafe(source.getLatEnc());
        double lon = bytesToDoubleSafe(source.getLngEnc());
        double[] normalized = toInternalWgs84(lat, lon, source.getRawCoordType());
        copy.setLatEnc(doubleToBytes(normalized[0]));
        copy.setLngEnc(doubleToBytes(normalized[1]));
        copy.setRawCoordType(INTERNAL_COORD_TYPE);
        return copy;
    }

    private CoordType resolveInputCoordType(Map<String, Object> map) {
        Object raw = map.get("coordType");
        if (raw instanceof String str) {
            try {
                return CoordType.valueOf(str.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignore) {
            }
        }
        return CoordType.GCJ02;
    }

    private double[] toInternalWgs84(double lat, double lon, CoordType sourceType) {
        if (sourceType == null || sourceType == INTERNAL_COORD_TYPE) {
            return new double[]{lat, lon};
        }
        if (sourceType == CoordType.GCJ02) {
            return gcj02ToWgs84(lat, lon);
        }
        return new double[]{lat, lon};
    }

    private double[] toDisplayCoord(double lat, double lon) {
        if (DISPLAY_COORD_TYPE == CoordType.GCJ02) {
            return wgs84ToGcj02(lat, lon);
        }
        return new double[]{lat, lon};
    }

    private CoordTypeVO toDisplayCoordTypeVO() {
        return DISPLAY_COORD_TYPE == CoordType.GCJ02 ? CoordTypeVO.GCJ02 : CoordTypeVO.WGS84;
    }

    private boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
        dLon = (dLon * 180.0) / ((6378245.0 / sqrtMagic) * Math.cos(radLat) * Math.PI);
        double mgLat = lat + dLat;
        double mgLon = lon + dLon;
        return new double[]{mgLat, mgLon};
    }

    private double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] gcj = wgs84ToGcj02(lat, lon);
        return new double[]{lat * 2 - gcj[0], lon * 2 - gcj[1]};
    }

    private double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private List<MapMatchingResult> simpleProjection(List<TrackPoint> points) {
        List<MapMatchingResult> result = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            result.add(buildRawFallbackResult(points.get(i), i));
        }
        return result;
    }

    private MapMatchingResult buildRawFallbackResult(TrackPoint point, int position) {
        MapMatchingResult result = new MapMatchingResult(
                point.getId(),
                bytesToDoubleSafe(point.getLatEnc()),
                bytesToDoubleSafe(point.getLngEnc()),
                0.1
        );
        result.setPosition(position);
        return result;
    }

    private MapMatchingResult buildMatchResult(TrackPoint point, CandidatePoint candidate, int position) {
        MapMatchingResult result = new MapMatchingResult(
                point.getId(),
                candidate.projectedLat,
                candidate.projectedLon,
                clamp01(candidate.observationProb)
        );
        result.setMatchedRoadId(candidate.road.getId());
        result.setMatchedRoadName(candidate.road.getName());
        result.setMatchedRoad(candidate.road);
        result.setPosition(position);
        return result;
    }

    private List<GeoPointVO> convertTrackPointsToGeoPoints(List<TrackPoint> points) {
        List<GeoPointVO> geoPoints = new ArrayList<>(points.size());
        for (TrackPoint point : points) {
            Float accuracy = point.getAccuracyM();
            double[] display = toDisplayCoord(bytesToDoubleSafe(point.getLatEnc()), bytesToDoubleSafe(point.getLngEnc()));
            geoPoints.add(GeoPointVO.builder()
                    .lat(display[0])
                    .lng(display[1])
                    .coordType(toDisplayCoordTypeVO())
                    .accuracyM(accuracy == null ? null : Double.valueOf(accuracy))
                    .ts(point.getTs())
                    .build());
        }
        return geoPoints;
    }

    private List<GeoPointVO> convertMatchedResultsToGeoPoints(List<MapMatchingResult> matched,
                                                              List<TrackPoint> points) {
        List<GeoPointVO> result = new ArrayList<>(matched.size());
        for (int i = 0; i < matched.size(); i++) {
            MapMatchingResult match = matched.get(i);
            Long ts = i < points.size() ? points.get(i).getTs() : null;
            double[] display = toDisplayCoord(match.getMatchedLatitude(), match.getMatchedLongitude());
            result.add(GeoPointVO.builder()
                    .lat(display[0])
                    .lng(display[1])
                    .coordType(toDisplayCoordTypeVO())
                    .ts(ts)
                    .build());
        }
        return result;
    }

    private List<GeoPointVO> reconstructPathWithRoadNetwork(List<MapMatchingResult> matched,
                                                            List<TrackPoint> points,
                                                            RoadNetwork roadNetwork) {
        List<GeoPointVO> reconstructed = new ArrayList<>();
        if (matched == null || matched.isEmpty()) {
            return reconstructed;
        }
        if (matched.size() == 1) {
            double[] display = toDisplayCoord(matched.get(0).getMatchedLatitude(), matched.get(0).getMatchedLongitude());
            reconstructed.add(GeoPointVO.builder()
                    .lat(display[0])
                    .lng(display[1])
                    .coordType(toDisplayCoordTypeVO())
                    .ts(points.get(0).getTs())
                    .build());
            return reconstructed;
        }

        Set<String> dedup = new HashSet<>();
        for (int i = 0; i < matched.size() - 1; i++) {
            MapMatchingResult start = matched.get(i);
            MapMatchingResult end = matched.get(i + 1);
            List<GeoPoint> pathPoints = findRoadPathPoints(start, end, roadNetwork);

            long startTs = i < points.size() ? safeTs(points.get(i)) : 0L;
            long endTs = i + 1 < points.size() ? safeTs(points.get(i + 1)) : startTs;
            long duration = Math.max(endTs - startTs, 1L);

            int from = i == 0 ? 0 : 1;
            for (int j = from; j < pathPoints.size(); j++) {
                GeoPoint point = pathPoints.get(j);
                double ratio = pathPoints.size() <= 1 ? 0.0 : (double) j / (pathPoints.size() - 1);
                long ts = startTs + (long) (duration * ratio);
                String key = String.format(Locale.ROOT, "%.6f,%.6f,%d", point.lat, point.lon, ts / 1000L);
                if (dedup.add(key)) {
                    double[] display = toDisplayCoord(point.lat, point.lon);
                    reconstructed.add(GeoPointVO.builder()
                            .lat(display[0])
                            .lng(display[1])
                            .coordType(toDisplayCoordTypeVO())
                            .ts(ts)
                            .build());
                }
            }
        }
        return reconstructed;
    }

    private List<GeoPoint> findRoadPathPoints(MapMatchingResult start,
                                              MapMatchingResult end,
                                              RoadNetwork roadNetwork) {
        List<GeoPoint> result = new ArrayList<>();
        double startLat = start.getMatchedLatitude();
        double startLon = start.getMatchedLongitude();
        double endLat = end.getMatchedLatitude();
        double endLon = end.getMatchedLongitude();
        result.add(new GeoPoint(startLat, startLon));

        RoadEdge startRoad = start.getMatchedRoad();
        RoadEdge endRoad = end.getMatchedRoad();
        if (startRoad == null || endRoad == null) {
            result.addAll(linearInterpolationPoints(startLat, startLon, endLat, endLon, LINEAR_INTERPOLATION_STEPS));
            return result;
        }

        Projection startProjection = projectPointToRoad(startLat, startLon, startRoad);
        Projection endProjection = projectPointToRoad(endLat, endLon, endRoad);

        if (sameRoad(startRoad, endRoad)) {
            if (startRoad.isOneWay() && endProjection.offsetFromStartMeters < startProjection.offsetFromStartMeters) {
                result.addAll(linearInterpolationPoints(startLat, startLon, endLat, endLon, LINEAR_INTERPOLATION_STEPS));
                return result;
            }
            result.addAll(linearInterpolationPoints(startLat, startLon, endLat, endLon, 3));
            return result;
        }

        NodePathChoice bestChoice = chooseBestNodePath(startRoad, startProjection.offsetFromStartMeters,
                endRoad, endProjection.offsetFromStartMeters, roadNetwork);
        double directDistance = calculateGreatCircleDistance(startLat, startLon, endLat, endLon);
        double maxReasonableDistance = Math.max(directDistance * MAX_RECONSTRUCTION_DETOUR_RATIO,
                directDistance + MAX_RECONSTRUCTION_EXTRA_METERS);
        if (bestChoice == null
                || bestChoice.totalCostMeters > maxReasonableDistance
                || bestChoice.nodePath.size() > MAX_RECONSTRUCTION_NODE_COUNT) {
            result.addAll(linearInterpolationPoints(startLat, startLon, endLat, endLon, LINEAR_INTERPOLATION_STEPS));
            return result;
        }

        appendPointIfFar(result, nodeToPoint(roadNetwork, bestChoice.startExit.nodeId));
        for (GeoPoint point : nodePathToPoints(bestChoice.nodePath, roadNetwork)) {
            appendPointIfFar(result, point);
        }
        appendPointIfFar(result, nodeToPoint(roadNetwork, bestChoice.endEntry.nodeId));
        appendPointIfFar(result, new GeoPoint(endLat, endLon));
        return result;
    }

    private List<GeoPoint> linearInterpolationPoints(double startLat,
                                                     double startLon,
                                                     double endLat,
                                                     double endLon,
                                                     int steps) {
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            double ratio = (double) i / steps;
            points.add(new GeoPoint(
                    startLat + (endLat - startLat) * ratio,
                    startLon + (endLon - startLon) * ratio
            ));
        }
        return points;
    }

    private Projection projectPointToRoad(double lat, double lon, RoadEdge road) {
        try {
            RoadEdge.Projection projection = road.project(lat, lon);
            return new Projection(
                    projection.getLat(),
                    projection.getLon(),
                    projection.getDistanceMeters(),
                    projection.getOffsetMeters(),
                    projection.getLocalDirectionDegrees()
            );
        } catch (Throwable ignore) {
            double meanLatRad = Math.toRadians((lat + road.getStartLat() + road.getEndLat()) / 3.0);
            double meterPerLat = 111_320.0;
            double meterPerLon = Math.cos(meanLatRad) * 111_320.0;

            double px = lon * meterPerLon;
            double py = lat * meterPerLat;
            double x1 = road.getStartLon() * meterPerLon;
            double y1 = road.getStartLat() * meterPerLat;
            double x2 = road.getEndLon() * meterPerLon;
            double y2 = road.getEndLat() * meterPerLat;

            double dx = x2 - x1;
            double dy = y2 - y1;
            double lenSq = dx * dx + dy * dy;
            if (lenSq <= 0.0) {
                return new Projection(road.getStartLat(), road.getStartLon(),
                        calculateGreatCircleDistance(lat, lon, road.getStartLat(), road.getStartLon()), 0.0,
                        safeDirection(road) == null ? 0.0 : safeDirection(road));
            }

            double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double projX = x1 + t * dx;
            double projY = y1 + t * dy;

            double projectedLon = projX / meterPerLon;
            double projectedLat = projY / meterPerLat;
            double distanceMeters = Math.hypot(px - projX, py - projY);
            double offsetFromStart = Math.sqrt(lenSq) * t;
            return new Projection(projectedLat, projectedLon, distanceMeters, offsetFromStart,
                    safeDirection(road) == null ? 0.0 : safeDirection(road));
        }
    }

    private BBox computeTrackBBox(List<TrackPoint> points) {
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (TrackPoint point : points) {
            double lat = bytesToDoubleSafe(point.getLatEnc());
            double lon = bytesToDoubleSafe(point.getLngEnc());
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);
        }
        return new BBox(minLat, minLon, maxLat, maxLon);
    }

    private Long calculateTotalDistance(List<GeoPointVO> points) {
        if (points == null || points.size() < 2) {
            return 0L;
        }
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += calculateGreatCircleDistance(
                    points.get(i).getLat(), points.get(i).getLng(),
                    points.get(i + 1).getLat(), points.get(i + 1).getLng()
            );
        }
        return Math.round(total);
    }

    private double calculateGreatCircleDistanceBetweenPoints(TrackPoint a, TrackPoint b) {
        return calculateGreatCircleDistance(
                bytesToDoubleSafe(a.getLatEnc()), bytesToDoubleSafe(a.getLngEnc()),
                bytesToDoubleSafe(b.getLatEnc()), bytesToDoubleSafe(b.getLngEnc())
        );
    }

    private double calculateGreatCircleDistance(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);

        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6_371_000.0 * c;
    }

    private double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2)
                - Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        if (bearing < 0.0) {
            bearing += 360.0;
        }
        return bearing;
    }

    private double calculateAngleDifference(Double angleA, Double angleB) {
        if (angleA == null || angleB == null) {
            return 180.0;
        }
        double diff = Math.abs(angleA - angleB) % 360.0;
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    private double gaussian(double value, double mean, double sigma) {
        double denominator = Math.sqrt(2.0 * Math.PI) * sigma;
        double exponent = -Math.pow(value - mean, 2) / (2.0 * sigma * sigma);
        return Math.exp(exponent) / denominator;
    }

    private int safeNodeDegree(RoadEdge road) {
        return road == null || road.getNodeDegree() == null ? 0 : road.getNodeDegree();
    }

    private Double safeDirection(RoadEdge road) {
        return road == null ? null : road.getDirection();
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0
                && !(Math.abs(lat) < 1e-8 && Math.abs(lon) < 1e-8);
    }

    private int argMax(double[] values) {
        int index = -1;
        double best = NEG_INF;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > best) {
                best = values[i];
                index = i;
            }
        }
        return index;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double bytesToDoubleSafe(byte[] bytes) {
        try {
            return bytesToDouble(bytes);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double bytesToDouble(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0.0;
        }
        long bits = 0L;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private byte[] doubleToBytes(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (bits >> (i * 8));
        }
        return bytes;
    }

    private enum MotionMode {
        WALKING,
        DRIVING
    }

    @Data
    @AllArgsConstructor
    private static class PointMotionEvidence {
        private TrackPoint point;
        private double roadDistanceMeters;
        private double medianSpeed;
        private double maxSpeed;
        private double windowAvgSpeed;
        private double nearRoadRatio;
        private double offRoadRatio;
        private double stayRatio;
        private double driveScore;
        private double walkScore;
    }

    @Data
    @AllArgsConstructor
    private static class MotionSegment {
        private MotionMode mode;
        private List<TrackPoint> points;
        private int startIndex;
        private int endIndex;
        private double nearRoadRatio;
        private double offRoadRatio;
        private double avgSpeed;
        private double maxSpeed;
    }

    @Data
    @AllArgsConstructor
    private static class SegmentPolylineResult {
        private MotionMode mode;
        private List<GeoPointVO> matchedPoints;
        private List<GeoPointVO> reconstructedPoints;
        private boolean reconstructedSimplified;
    }

    @Data
    @AllArgsConstructor
    private static class ProcessedPolylineBundle {
        private List<GeoPointVO> matchedPoints;
        private List<GeoPointVO> reconstructedPoints;
        private boolean reconstructedSimplified;
        private List<TrackPolylineVO.SegmentVO> matchedSegments;
        private List<TrackPolylineVO.SegmentVO> reconstructedSegments;
    }

    private static class MatchingRuntimeContext {
        private int depth;
        private final Map<String, List<RoadEdge>> nearbyEdgeCache = newLruCache(MAX_NEARBY_EDGE_CACHE_SIZE);
        private final Map<String, Double> routeDistanceCache = newLruCache(MAX_ROUTE_DISTANCE_CACHE_SIZE);
    }

    private static <K, V> Map<K, V> newLruCache(final int maxSize) {
        return new LinkedHashMap<K, V>(Math.min(maxSize, 128), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }

    @Data
    @AllArgsConstructor
    private static class NodeAccessOption {
        private long nodeId;
        private double costMeters;
    }

    @Data
    @AllArgsConstructor
    private static class NodePathChoice {
        private NodeAccessOption startExit;
        private NodeAccessOption endEntry;
        private List<Long> nodePath;
        private double totalCostMeters;
    }

    @Data
    @AllArgsConstructor
    private static class Projection {
        private double lat;
        private double lon;
        private double distanceMeters;
        private double offsetFromStartMeters;
        private double localDirectionDegrees;
    }

    @Data
    @AllArgsConstructor
    private static class BBox {
        private double minLat;
        private double minLon;
        private double maxLat;
        private double maxLon;
    }

    @Data
    @AllArgsConstructor
    private static class PointComplexity {
        private double directional;
        private double connectivity;
        private double overall;
        private boolean complex;
    }

    @Data
    @AllArgsConstructor
    private static class CompressedWindow {
        private int startIndex;
        private int endIndex;
        private TrackPoint representative;
        private List<TrackPoint> members;
    }

    @Data
    @AllArgsConstructor
    private static class TrajectorySegment {
        private List<TrackPoint> points;
        private boolean complex;
        private int startWindowIndex;
        private int endWindowIndex;
        private double averageComplexity;
        private List<Double> complexities;
    }

    @Data
    @AllArgsConstructor
    private static class CandidatePoint {
        private TrackPoint trackPoint;
        private RoadEdge road;
        private double projectedLat;
        private double projectedLon;
        private double distanceMeters;
        private double thetaDegrees;
        private double observationProb;
        private double offsetFromStartMeters;
        private double localDirectionDegrees;

        public Double roadDirection() {
            if (Double.isNaN(localDirectionDegrees)) {
                return road == null ? null : road.getDirection();
            }
            return localDirectionDegrees;
        }
    }
}
