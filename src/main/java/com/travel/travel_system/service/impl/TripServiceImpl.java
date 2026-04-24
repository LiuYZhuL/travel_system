package com.travel.travel_system.service.impl;

import com.travel.travel_system.dto.TripRouteSnapshotPayload;
import com.travel.travel_system.model.*;
import com.travel.travel_system.dto.MapMatchingResult;
import com.travel.travel_system.model.enums.BlockType;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.TrackPointSource;
import com.travel.travel_system.model.enums.TripStatus;
import com.travel.travel_system.repository.*;
import com.travel.travel_system.service.*;
import com.travel.travel_system.service.pub.RedisService;
import com.travel.travel_system.vo.*;
import com.travel.travel_system.vo.enums.*;
import com.travel.travel_system.utils.DateTimeUtils;
import com.travel.travel_system.utils.GeoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TripServiceImpl implements TripService {

    private static final Logger log = LoggerFactory.getLogger(TripServiceImpl.class);
    private static final long DEFAULT_PROCESSING_RETRY_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final String SNAPSHOT_LOCK_KEY_PREFIX = "track_match:snapshot:lock:";
    private static final Set<Long> FINALIZING_TRIP_IDS = ConcurrentHashMap.newKeySet();

    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TrackPointRepository trackPointRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;
    @Autowired
    private TripBBoxRepository tripBBoxRepository;
    @Autowired
    private AnchorRepository anchorRepository;
    @Autowired
    private TripNoteRepository tripNoteRepository;
    @Autowired
    private StoryBlockRepository storyBlockRepository;
    @Autowired
    private TripAiSummaryRepository tripAiSummaryRepository;
    @Autowired
    private AiService aiService;
    @Autowired
    private TrackPointServiceImpl trackPointService;
    @Autowired
    private TripRouteSnapshotService tripRouteSnapshotService;
    @Autowired
    private RedisService redisService;
    @Autowired
    private TripSegmentRepository tripSegmentRepository;
    @Autowired
    private PlaceSummaryService placeSummaryService;
    @Autowired
    private TripAggregationRefreshService tripAggregationRefreshService;

    @Value("${app.trip.processing-retry-timeout-ms:300000}")
    private long processingRetryTimeoutMs;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static final SimpleDateFormat DATE_ONLY_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    static {
        TimeZone tz = TimeZone.getTimeZone("Asia/Shanghai");
        DATE_FORMAT.setTimeZone(tz);
        DATE_ONLY_FORMAT.setTimeZone(tz);
    }


    @Override
    @Transactional
    public Trip createTrip(Long userId, String title, String timezone, String privacyMode, String startTime) {
        Trip trip = new Trip();
        trip.setUserId(userId);
        trip.setTitle(title != null && !title.trim().isEmpty() ? title : "未命名行程");
        trip.setTimezone(timezone != null ? timezone : "Asia/Shanghai");
        trip.setPrivacyMode(parsePrivacyModeOrDefault(privacyMode, PrivacyMode.PUBLIC));
        trip.setStatus(TripStatus.ACTIVE);
        try {
            trip.setStartTime(startTime != null && !startTime.trim().isEmpty() ? DATE_FORMAT.parse(startTime) : new Date());
        } catch (Exception e) {
            trip.setStartTime(new Date());
        }
        trip.setDistanceM(0L);
        trip.setDurationSec(0L);
        trip.setPhotoCount(0);
        trip.setVideoCount(0);
        trip.setCreatedAt(new Date());
        trip.setUpdatedAt(new Date());

        Trip saved = tripRepository.save(trip);
        openFirstSegmentIfAbsent(saved);
        return saved;
    }

    @Override
    public Optional<Trip> getTrip(Long tripId) {
        return tripRepository.findById(tripId);
    }

    @Override
    public Trip getUserTripOrThrow(Long userId, Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (!Objects.equals(trip.getUserId(), userId)) {
            throw new RuntimeException("无权访问该行程");
        }
        recoverProcessingTripIfStuck(trip);
        return trip;
    }

    @Override
    public Page<Trip> getUserTripsPage(Long userId, Pageable pageable, String status) {
        if (status != null && !status.trim().isEmpty()) {
            try {
                return tripRepository.findByUserIdAndStatus(userId, TripStatus.valueOf(status), pageable);
            } catch (Exception ignored) {
            }
        }
        return tripRepository.findByUserId(userId, pageable);
    }

    @Override
    public Page<Trip> searchUserTrips(Long userId, Pageable pageable, String keyword, String status, String startDate, String endDate) {
        Page<Trip> tripPage = tripRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (keyword != null && !keyword.trim().isEmpty()) {
                predicates.add(cb.like(root.get("title"), "%" + keyword.trim() + "%"));
            }
            if (status != null && !status.trim().isEmpty()) {
                try {
                    predicates.add(cb.equal(root.get("status"), TripStatus.valueOf(status.trim())));
                } catch (IllegalArgumentException ignored) {
                }
            }
            boolean hasStartDate = startDate != null && !startDate.trim().isEmpty();
            boolean hasEndDate = endDate != null && !endDate.trim().isEmpty();
            if (hasStartDate || hasEndDate) {
                try {
                    if (hasStartDate && hasEndDate) {
                        Date start = DATE_ONLY_FORMAT.parse(startDate.trim());
                        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                        calendar.setTime(DATE_ONLY_FORMAT.parse(endDate.trim()));
                        calendar.add(Calendar.DAY_OF_MONTH, 1);
                        predicates.add(cb.between(root.get("startTime"), start, calendar.getTime()));
                    } else if (hasStartDate) {
                        Date start = DATE_ONLY_FORMAT.parse(startDate.trim());
                        predicates.add(cb.greaterThanOrEqualTo(root.get("startTime"), start));
                    } else {
                        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
                        calendar.setTime(DATE_ONLY_FORMAT.parse(endDate.trim()));
                        calendar.add(Calendar.DAY_OF_MONTH, 1);
                        predicates.add(cb.lessThan(root.get("startTime"), calendar.getTime()));
                    }
                } catch (ParseException ignored) {
                }
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, pageable);
        tripPage.getContent().forEach(this::recoverProcessingTripIfStuck);
        return tripPage;
    }

    @Override
    @Transactional
    public Trip updateTripBasic(Long userId, Long tripId, String title, String privacyMode) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        if (title != null && !title.trim().isEmpty()) {
            trip.setTitle(title.trim());
        }
        if (privacyMode != null && !privacyMode.trim().isEmpty()) {
            trip.setPrivacyMode(parsePrivacyModeOrDefault(privacyMode, trip.getPrivacyMode()));
        }
        trip.setUpdatedAt(new Date());
        Trip saved = tripRepository.save(trip);
        tripAggregationRefreshService.markTripDirty(tripId, "TRIP_UPDATE");
        return saved;
    }

    @Override
    @Transactional
    public void updateTripPrivacy(Long tripId, String privacyMode) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        trip.setPrivacyMode(parsePrivacyModeOrDefault(privacyMode, trip.getPrivacyMode()));
        trip.setUpdatedAt(new Date());
        tripRepository.save(trip);
        tripAggregationRefreshService.markTripDirty(tripId, "TRIP_PRIVACY_UPDATE");
    }

    @Override
    @Transactional
    public void deleteTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        trackPointRepository.deleteByTripId(tripId);
        photoRepository.deleteByTripId(tripId);
        videoRepository.deleteByTripId(tripId);
        anchorRepository.deleteByTripId(tripId);
        placeSummaryRepository.deleteByTripId(tripId);
        tripNoteRepository.deleteByTripId(tripId);
        storyBlockRepository.deleteByTripId(tripId);
        tripAiSummaryRepository.deleteByTripId(tripId);
        tripBBoxRepository.deleteByTripId(tripId);

        tripSegmentRepository.deleteByTripId(tripId);

        tripRepository.delete(trip);
    }

    @Override
    @Transactional
    public Trip finishTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (trip.getStatus() != TripStatus.ACTIVE && trip.getStatus() != TripStatus.PAUSED) {
            throw new RuntimeException("行程状态不允许结束，当前状态：" + trip.getStatus());
        }

        long nowTs = System.currentTimeMillis();
        trip.setEndTime(new Date(nowTs));
        trip.setStatus(TripStatus.PROCESSING);
        trip.setUpdatedAt(new Date());

        Trip saved = tripRepository.save(trip);

        closeOpenSegment(tripId, nowTs, "FINISH");
        triggerFinalizeTripAsync(tripId);

        return saved;
    }
    @Async
    public void finalizeTripAsync(Long tripId) {
        runFinalizeTrip(tripId);
    }

    @Async
    public void refreshLatestSnapshotAsync(Long tripId) {
        try {
            tripRouteSnapshotService.saveLatestSnapshot(tripId);
        } catch (RuntimeException e) {
            if (isSnapshotRefreshInProgressError(e)) {
                log.debug("[TRIP_ROUTE_SNAPSHOT] async refresh skipped tripId={} reason=in_progress", tripId);
                return;
            }
            log.warn("[TRIP_ROUTE_SNAPSHOT] async refresh failed tripId={}: {}", tripId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("[TRIP_ROUTE_SNAPSHOT] async refresh failed tripId={}: {}", tripId, e.getMessage(), e);
        }
    }


    @Override
    @Transactional
    public Trip pauseTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new RuntimeException("TRIP_409 只有进行中的行程才可暂停");
        }

        long nowTs = System.currentTimeMillis();
        trip.setStatus(TripStatus.PAUSED);
        trip.setUpdatedAt(new Date());
        Trip saved = tripRepository.save(trip);

        closeOpenSegment(tripId, nowTs, "PAUSE");
        return saved;
    }

    @Override
    @Transactional
    public Trip resumeTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (trip.getStatus() != TripStatus.PAUSED) {
            throw new RuntimeException("TRIP_409 只有暂停状态的行程才可恢复");
        }

        long nowTs = System.currentTimeMillis();
        trip.setStatus(TripStatus.ACTIVE);
        trip.setUpdatedAt(new Date());
        Trip saved = tripRepository.save(trip);

        TripSegment openSegment = tripSegmentRepository
                .findTopByTripIdAndIsClosedFalseOrderBySegmentNoDesc(tripId)
                .orElse(null);

        if (openSegment == null) {
            openNextSegment(tripId, nowTs, "RESUME");
        }

        return saved;
    }

    @Override
    @Transactional
    public Trip settleTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 只用 ACTIVE 段里可绘制的点做最终结算
        List<TrackPoint> effectiveTrackPoints = getEffectiveTrackPoints(tripId);

        if (!effectiveTrackPoints.isEmpty()) {
            trip.setDistanceM(calculateTotalDistance(effectiveTrackPoints));

            if (effectiveTrackPoints.size() >= 2) {
                long duration = (effectiveTrackPoints.get(effectiveTrackPoints.size() - 1).getTs()
                        - effectiveTrackPoints.get(0).getTs()) / 1000L;
                trip.setDurationSec(Math.max(duration, 0L));
            } else {
                trip.setDurationSec(0L);
            }

            calculateAndSaveBBox(tripId, effectiveTrackPoints);

            // 路径匹配也应该只基于有效轨迹点
            performMapMatching(tripId);

            // 路线快照生成依赖 TrackPointServiceImpl；
            // 你那边也要保证 matchTrajectory/processTrackRendering 只使用 renderEligible=true 的点
            tripRouteSnapshotService.finalizeFinishedTrip(tripId);
        } else {
            trip.setDistanceM(0L);
            trip.setDurationSec(0L);
        }

        trip.setPhotoCount((int) photoRepository.countByTripId(tripId));
        trip.setVideoCount((int) videoRepository.countByTripId(tripId));

        try {
            placeSummaryService.generatePlaceSummariesForTrip(tripId);
        } catch (Exception e) {
            log.warn("[settleTrip] 生成地点摘要失败: tripId={}, error={}", tripId, e.getMessage());
        }

        try {
            aiService.rebuildStoryBlocks(tripId);
        } catch (Exception ignored) {
        }

        trip.setStatus(TripStatus.FINISHED);
        trip.setGeneratedAt(new Date());
        trip.setUpdatedAt(new Date());
        return tripRepository.save(trip);
    }

    @Override
    public Map<String, Object> getTripStatistics(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("tripId", tripId);
        statistics.put("title", trip.getTitle());
        statistics.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
        statistics.put("distanceM", defaultLong(trip.getDistanceM()));
        statistics.put("distanceText", formatDistance(trip.getDistanceM()));
        statistics.put("durationSec", defaultLong(trip.getDurationSec()));
        statistics.put("durationText", DateTimeUtils.formatDuration(trip.getDurationSec()));
        TripMediaCounts mediaCounts = resolveTripMediaCounts(tripId);
        statistics.put("photoCount", mediaCounts.photoCount());
        statistics.put("videoCount", mediaCounts.videoCount());
        statistics.put("noteCount", (int) tripNoteRepository.countByTripId(tripId));
        statistics.put("placeCount", (int) placeSummaryRepository.countByTripId(tripId));
        statistics.put("trackPointCount", (int) trackPointRepository.countByTripId(tripId));
        statistics.put("anchorCount", (int) anchorRepository.countByTripId(tripId));
        return statistics;
    }

    @Override
    public Map<String, Object> getTripStory(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("tripId", tripId);
        story.put("title", trip.getTitle());
        Optional<TripAiSummary> aiSummary = tripAiSummaryRepository.findFirstByTripIdAndIsLatestTrueOrderByGeneratedAtDescIdDesc(tripId);
        if (aiSummary.isPresent()) {
            TripAiSummary summary = aiSummary.get();
            story.put("overview", summary.getOverview());
            story.put("highlights", parseHighlights(summary.getHighlights()));
            story.put("routeSummary", summary.getRouteSummary());
            story.put("bestMoment", summary.getBestMoment());
        } else {
            story.put("overview", buildDefaultStoryLive(trip));
            story.put("highlights", buildDefaultHighlightsLive(trip));
            story.put("routeSummary", "行程路线信息");
            story.put("bestMoment", "旅途中的美好瞬间");
        }
        return story;
    }

    @Override
    public TripDetailVO getTripDetail(Long userId, Long tripId) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        TripAISummaryVO aiSummary = resolveTripAiSummaryVO(trip, false);
        return TripDetailVO.builder()
                .trip(buildTripSummary(trip, (int) placeSummaryRepository.countByTripId(tripId), aiSummary))
                .map(buildTripMap(trip))
                .places(getTripPlaces(userId, tripId))
                .storyBlocks(buildLiveStoryBlocks(trip))
                .aiSummary(aiSummary)
                .shareAllowed(trip.getPrivacyMode() != PrivacyMode.PRIVATE)
                .shareMode(trip.getPrivacyMode() != null ? trip.getPrivacyMode().name() : PrivacyMode.PUBLIC.name())
                .shareHint(buildShareHint(trip.getPrivacyMode()))
                .build();
    }

    @Override
    public TripDetailVO getPublicTripDetail(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        PrivacyMode privacyMode = trip.getPrivacyMode() != null ? trip.getPrivacyMode() : PrivacyMode.PUBLIC;
        TripAISummaryVO aiSummary = resolveTripAiSummaryVO(trip, false);
        TripDetailVO.TripSummaryVO summary = buildTripSummary(trip, (int) placeSummaryRepository.countByTripId(tripId), aiSummary);

        if (privacyMode == PrivacyMode.PRIVATE) {
            return TripDetailVO.builder()
                    .trip(maskedTripSummary(summary, true))
                    .map(null)
                    .places(Collections.emptyList())
                    .storyBlocks(Collections.emptyList())
                    .aiSummary(null)
                    .shareAllowed(Boolean.FALSE)
                    .shareMode(privacyMode.name())
                    .shareHint(buildShareHint(privacyMode))
                    .build();
        }

        if (privacyMode == PrivacyMode.MASKED) {
            return TripDetailVO.builder()
                    .trip(maskedTripSummary(summary, false))
                    .map(null)
                    .places(Collections.emptyList())
                    .storyBlocks(Collections.emptyList())
                    .aiSummary(null)
                    .shareAllowed(Boolean.TRUE)
                    .shareMode(privacyMode.name())
                    .shareHint(buildShareHint(privacyMode))
                    .build();
        }

        List<PlaceSummaryVO> sharedPlaces = buildSharePlaces(tripId);
        List<StoryBlockVO> sharedStoryBlocks = buildShareStoryBlocks(trip);

        return TripDetailVO.builder()
                .trip(buildSharedTripSummary(summary, trip))
                .map(sanitizeShareMap(buildTripMap(trip)))
                .places(sharedPlaces)
                .storyBlocks(sharedStoryBlocks)
                .aiSummary(aiSummary)
                .shareAllowed(Boolean.TRUE)
                .shareMode(privacyMode.name())
                .shareHint(buildShareHint(privacyMode))
                .build();
    }

    @Override
    public TripMapVO getTripMap(Long userId, Long tripId) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        return buildTripMap(trip);
    }

    private TripMapVO buildTripMap(Trip trip) {
        Long tripId = trip.getId();
        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        List<TripSegment> segments = tripSegmentRepository.findByTripIdOrderBySegmentNoAsc(tripId);

        CoordTypeVO displayCoordType = resolveDisplayCoordType(trackPoints);
        TripMapVO.BBoxVO bbox = buildBBox(tripId, trackPoints, displayCoordType);
        GeoPointVO center = buildCenter(bbox, displayCoordType);

        List<MapMarkerVO> mediaMarkers = buildMediaMarkersForMap(tripId, displayCoordType);
        List<TrackPolylineVO> rawSegments = buildRawSegmentsForMap(segments, trackPoints, displayCoordType);
        RouteMapPayload routePayload = resolveRouteMapPayload(trip, displayCoordType);

        return TripMapVO.builder()
                .center(center)
                .zoom(resolveZoom(bbox))
                .bbox(bbox)
                .rawPolyline(mergeTrackPolylineList(rawSegments))
                .matchedPolyline(routePayload.matchedPolyline)
                .reconstructedPolyline(routePayload.reconstructedPolyline)
                .rawSegments(rawSegments.isEmpty() ? null : rawSegments)
                .matchedSegments(routePayload.matchedSegments)
                .reconstructedSegments(routePayload.reconstructedSegments)
                .markers(buildMapMarkers(tripId, displayCoordType, trackPoints))
                .mediaMarkers(mediaMarkers)
                .matchingDiagnostics(routePayload.matchingDiagnostics)
                .routeSource(routePayload.routeSource)
                .routeSyncStatus(routePayload.routeSyncStatus)
                .routeGeneratedAt(routePayload.routeGeneratedAt)
                .build();
    }
    @Override
    public List<PlaceSummaryVO> getTripPlaces(Long userId, Long tripId) {
        getUserTripOrThrow(userId, tripId);
        return placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId)
                .stream().map(this::toPlaceSummaryVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getTripStoryBlocks(Long userId, Long tripId, Integer pageNo, Integer pageSize) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        List<StoryBlockVO> allItems = buildLiveStoryBlocks(trip);
        int safePageNo = Math.max(pageNo == null ? 1 : pageNo, 1);
        int safePageSize = Math.max(pageSize == null ? 20 : pageSize, 1);
        int fromIndex = Math.min((safePageNo - 1) * safePageSize, allItems.size());
        int toIndex = Math.min(fromIndex + safePageSize, allItems.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", allItems.subList(fromIndex, toIndex));
        data.put("total", allItems.size());
        data.put("pageNo", safePageNo);
        data.put("pageSize", safePageSize);
        return data;
    }

    @Override
    public TripAISummaryVO getTripAiSummary(Long userId, Long tripId, boolean regenerate) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        return resolveTripAiSummaryVO(trip, regenerate);
    }

    private TripAISummaryVO resolveTripAiSummaryVO(Trip trip, boolean regenerate) {
        if (trip == null || trip.getId() == null) {
            return null;
        }
        Long tripId = trip.getId();
        if (regenerate) {
            return toTripAiSummaryVO(tripId, aiService.generateTripSummary(tripId));
        }

        Optional<TripAiSummary> existing = tripAiSummaryRepository.findFirstByTripIdAndIsLatestTrueOrderByGeneratedAtDescIdDesc(tripId);
        Date latestContentAt = resolveTripSummaryContentUpdatedAt(trip);
        if (existing.isPresent() && !isTripAiSummaryStale(existing.get(), latestContentAt)) {
            return toTripAiSummaryVO(existing.get());
        }
        if (existing.isPresent()) {
            return toTripAiSummaryVO(existing.get());
        }
        return buildPendingTripAiSummaryVO(trip);
    }

    private TripAISummaryVO toTripAiSummaryVO(TripAiSummary summary) {
        if (summary == null) {
            return null;
        }
        return TripAISummaryVO.builder()
                .tripId(summary.getTripId())
                .overview(summary.getOverview())
                .highlights(parseHighlights(summary.getHighlights()))
                .routeSummary(summary.getRouteSummary())
                .bestMoment(summary.getBestMoment())
                .generatedAt(DateTimeUtils.formatDateTime(summary.getGeneratedAt()))
                .version(summary.getVersion())
                .build();
    }

    private TripAISummaryVO buildPendingTripAiSummaryVO(Trip trip) {
        if (trip == null || trip.getId() == null) {
            return null;
        }
        return TripAISummaryVO.builder()
                .tripId(trip.getId())
                .overview(buildDefaultStoryLive(trip))
                .highlights(buildDefaultHighlightsLive(trip))
                .routeSummary("正在根据最新行程内容整理路线与地点摘要")
                .bestMoment("聚合刷新完成后会补充更有代表性的行程片段")
                .generatedAt(null)
                .version("PENDING")
                .build();
    }

    private boolean isTripAiSummaryStale(TripAiSummary summary, Date latestContentAt) {
        if (summary == null) {
            return true;
        }
        if (latestContentAt == null) {
            return false;
        }
        return summary.getGeneratedAt() == null || summary.getGeneratedAt().before(latestContentAt);
    }

    private Date resolveTripSummaryContentUpdatedAt(Trip trip) {
        if (trip == null || trip.getId() == null) {
            return null;
        }
        Date latest = null;
        if (trip.getStatus() == TripStatus.FINISHED) {
            latest = maxDate(latest, trip.getUpdatedAt());
            latest = maxDate(latest, trip.getEndTime());
        }
        for (PlaceSummary place : placeSummaryRepository.findByTripIdOrderByStartTimeAsc(trip.getId())) {
            latest = maxDate(latest, place.getUpdatedAt());
            latest = maxDate(latest, place.getGeneratedAt());
        }
        for (Photo photo : photoRepository.findByTripId(trip.getId())) {
            latest = maxDate(latest, photo.getCreatedAt());
        }
        for (Video video : videoRepository.findByTripId(trip.getId())) {
            latest = maxDate(latest, video.getCreatedAt());
        }
        for (TripNote note : tripNoteRepository.findByTripIdOrderByCreatedAtDesc(trip.getId())) {
            latest = maxDate(latest, note.getUpdatedAt());
            latest = maxDate(latest, note.getCreatedAt());
        }
        return latest;
    }

    private Date maxDate(Date current, Date candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.after(current)) {
            return candidate;
        }
        return current;
    }

    @Override
    public List<StoryBlockVO> rebuildTripStoryBlocks(Long userId, Long tripId) {
        getUserTripOrThrow(userId, tripId);
        return aiService.rebuildStoryBlocks(tripId).stream().map(this::toStoryBlockVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getActiveTrip(Long userId) {
        List<Trip> activeTrips = tripRepository.findByUserIdAndStatus(userId, TripStatus.ACTIVE);
        List<Trip> processingTrips = tripRepository.findByUserIdAndStatus(userId, TripStatus.PROCESSING);
        if (processingTrips != null && !processingTrips.isEmpty()) {
            processingTrips.forEach(this::recoverProcessingTripIfStuck);
            activeTrips = new ArrayList<>(activeTrips == null ? Collections.emptyList() : activeTrips);
            activeTrips.addAll(processingTrips);
        }
        if (activeTrips == null || activeTrips.isEmpty()) {
            return null;
        }

        activeTrips.sort(Comparator.comparing(
                Trip::getStartTime,
                Comparator.nullsLast(Date::compareTo)
        ).reversed());

        Trip trip = activeTrips.get(0);
        TripMediaCounts mediaCounts = resolveTripMediaCounts(trip.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", stringifyId(trip.getId()));
        data.put("title", trip.getTitle());
        data.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
        data.put("privacyMode", trip.getPrivacyMode() != null ? trip.getPrivacyMode().name() : null);
        data.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
        data.put("distanceText", formatDistance(trip.getDistanceM()));
        data.put("durationSec", defaultLong(trip.getDurationSec()));
        data.put("photoCount", mediaCounts.photoCount());
        data.put("videoCount", mediaCounts.videoCount());
        data.put("placeCount", (int) placeSummaryRepository.countByTripId(trip.getId()));
        data.put("summaryText", resolveTripSummaryText(trip));
        MediaAssetVO cover = buildTripCoverMedia(trip.getId());
        data.put("coverUrl", cover != null ? cover.getUrl() : null);
        return data;
    }

    @Override
    @Transactional
    public Integer uploadTrackPoints(Long userId, Long tripId, List<Map<String, Object>> points) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        if (points == null || points.isEmpty()) {
            return 0;
        }

        if (trip.getStatus() == TripStatus.FINISHED || trip.getStatus() == TripStatus.PROCESSING) {
            throw new RuntimeException("行程已结束或正在归档，不能继续写入轨迹点");
        }

        // P0 修复：PAUSED 期间不再写入主轨迹表，避免污染 segment / media 归属 / route 计算
        if (trip.getStatus() == TripStatus.PAUSED) {
            return 0;
        }

        TripSegment openSegment = tripSegmentRepository
                .findTopByTripIdAndIsClosedFalseOrderBySegmentNoDesc(tripId)
                .orElse(null);

        if (openSegment == null) {
            Long batchStartTs = resolveBatchStartTs(points);
            openSegment = openNextSegment(
                    tripId,
                    batchStartTs != null ? batchStartTs : System.currentTimeMillis(),
                    "RESUME"
            );
        }

        List<TrackPoint> trackPoints = new ArrayList<>();
        for (Map<String, Object> pointMap : points) {
            Double lat = toDouble(pointMap.get("lat"));
            Double lng = toDouble(pointMap.get("lng"));
            Long ts = toLong(pointMap.get("ts"));
            if (lat == null || lng == null || ts == null) {
                continue;
            }

            TrackPoint point = new TrackPoint();
            point.setUserId(trip.getUserId());
            point.setTripId(tripId);
            point.setTs(ts);
            point.setLatEnc(doubleToBytes(lat));
            point.setLngEnc(doubleToBytes(lng));
            point.setAccuracyM(toFloat(pointMap.get("accuracyM")));
            point.setSpeedMps(toFloat(pointMap.get("speedMps")));
            point.setHeadingDeg(toFloat(pointMap.get("headingDeg")));
            point.setSource(TrackPointSource.WX_FG);
            point.setRawCoordType(parseCoordTypeOrDefault(pointMap.get("coordType"), CoordType.GCJ02));
            point.setCreatedAt(new Date());

            point.setSegmentId(openSegment.getId());
            point.setRenderEligible(true);

            trackPoints.add(point);
        }

        if (!trackPoints.isEmpty()) {
            trackPointService.cacheTrackPoints(tripId, trackPoints);
            tripAggregationRefreshService.markTripDirty(tripId, "TRACK_POINT_BATCH");
        }

        return trackPoints.size();
    }
    @Override
    public Map<String, Object> getTrackStatus(Long userId, Long tripId) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        long totalCount = trackPointRepository.countByTripId(tripId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", tripId);
        data.put("processing", trip.getStatus() == TripStatus.PROCESSING);
        data.put("processedCount", trip.getStatus() == TripStatus.PROCESSING ? 0 : totalCount);
        data.put("totalCount", totalCount);
        data.put("lastUpdatedAt", DateTimeUtils.formatDateTime(trip.getUpdatedAt()));
        return data;
    }

    @Override
    public Map<String, Object> getUserTripStats(Long userId) {
        long tripCount = tripRepository.countByUserId(userId);
        Long totalDistanceM = tripRepository.sumDistanceByUserId(userId);
        Long totalDurationSec = tripRepository.sumDurationByUserId(userId);
        Integer totalPhotoCount = tripRepository.sumPhotoCountByUserId(userId);
        Integer totalVideoCount = tripRepository.sumVideoCountByUserId(userId);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tripCount", tripCount);
        stats.put("totalDistanceM", totalDistanceM != null ? totalDistanceM : 0L);
        stats.put("totalDistanceText", formatDistance(totalDistanceM));
        stats.put("totalDurationSec", totalDurationSec != null ? totalDurationSec : 0L);
        stats.put("totalDurationText", DateTimeUtils.formatDuration(totalDurationSec));
        stats.put("totalPhotoCount", totalPhotoCount != null ? totalPhotoCount : 0);
        stats.put("totalVideoCount", totalVideoCount != null ? totalVideoCount : 0);
        return stats;
    }

    private List<TrackPolylineVO> buildRawSegmentsForMap(List<TripSegment> segments, List<TrackPoint> allTrackPoints, CoordTypeVO displayCoordType) {
        List<TrackPolylineVO> rawSegments = new ArrayList<>();
        if (allTrackPoints == null || allTrackPoints.isEmpty()) {
            return rawSegments;
        }

        if (segments == null || segments.isEmpty()) {
            List<TrackPoint> eligiblePoints = allTrackPoints.stream()
                    .filter(point -> Boolean.TRUE.equals(point.getRenderEligible()))
                    .collect(Collectors.toList());
            if (!eligiblePoints.isEmpty()) {
                rawSegments.add(buildRawPolylineForMap(eligiblePoints, displayCoordType));
            }
            return rawSegments;
        }

        for (TripSegment segment : segments) {
            List<TrackPoint> segmentPoints = new ArrayList<>();
            for (TrackPoint point : allTrackPoints) {
                if (Objects.equals(point.getSegmentId(), segment.getId())
                        && Boolean.TRUE.equals(point.getRenderEligible())) {
                    segmentPoints.add(point);
                }
            }
            if (!segmentPoints.isEmpty()) {
                rawSegments.add(buildRawPolylineForMap(segmentPoints, displayCoordType));
            }
        }
        return rawSegments;
    }

    private TrackPolylineVO buildRawPolylineForMap(List<TrackPoint> rawTrackPoints, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        if (rawTrackPoints != null) {
            for (TrackPoint point : rawTrackPoints) {
                GeoPointVO geoPoint = buildGeoPoint(
                        point.getLatEnc(),
                        point.getLngEnc(),
                        null,
                        null,
                        point.getRawCoordType(),
                        displayCoordType
                );
                if (geoPoint != null) {
                    points.add(geoPoint);
                }
            }
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private TrackPolylineVO buildMatchedPolylineForMap(List<MapMatchingResult> results, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        if (results != null) {
            for (MapMatchingResult result : results) {
                if (!isRoadMatchedResult(result)) {
                    continue;
                }
                if (result.getMatchedLatitude() == null || result.getMatchedLongitude() == null) {
                    continue;
                }
                double[] display = GeoUtils.wgs84ToGcj02(result.getMatchedLatitude(), result.getMatchedLongitude());
                points.add(GeoPointVO.builder()
                        .lat(displayCoordType == CoordTypeVO.GCJ02 ? display[0] : result.getMatchedLatitude())
                        .lng(displayCoordType == CoordTypeVO.GCJ02 ? display[1] : result.getMatchedLongitude())
                        .coordType(displayCoordType)
                        .build());
            }
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private TrackPolylineVO buildReconstructedPolylineForMap(List<MapMatchingResult> results, CoordTypeVO displayCoordType) {
        List<GeoPointVO> points = new ArrayList<>();
        Double lastLat = null;
        Double lastLng = null;
        if (results != null) {
            for (MapMatchingResult result : results) {
                if (result.getMatchedLatitude() == null || result.getMatchedLongitude() == null) {
                    continue;
                }
                double[] display = GeoUtils.wgs84ToGcj02(result.getMatchedLatitude(), result.getMatchedLongitude());
                double lat = displayCoordType == CoordTypeVO.GCJ02 ? display[0] : result.getMatchedLatitude();
                double lng = displayCoordType == CoordTypeVO.GCJ02 ? display[1] : result.getMatchedLongitude();
                if (lastLat != null && Math.abs(lastLat - lat) < 1e-7 && Math.abs(lastLng - lng) < 1e-7) {
                    continue;
                }
                points.add(GeoPointVO.builder().lat(lat).lng(lng).coordType(displayCoordType).build());
                lastLat = lat;
                lastLng = lng;
            }
        }
        return TrackPolylineVO.builder().points(points).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private boolean isRoadMatchedResult(MapMatchingResult result) {
        if (result == null) {
            return false;
        }
        return "ROAD".equalsIgnoreCase(result.getMatchMode())
                && result.getMatchedLatitude() != null
                && result.getMatchedLongitude() != null;
    }

    private RouteMapPayload resolveRouteMapPayload(Trip trip, CoordTypeVO displayCoordType) {
        RouteMapPayload payload = new RouteMapPayload();
        payload.routeSource = "RAW_ONLY";
        payload.routeSyncStatus = "EMPTY";

        Long tripId = trip.getId();
        TripRouteSnapshotPayload latestPayload = trackPointService.buildRouteSnapshotPayload(tripId);
        String latestFingerprint = latestPayload.getFingerprint();
        boolean latestHasMatched = latestPayload.getMatchedResults() != null && !latestPayload.getMatchedResults().isEmpty();

        TripRouteSnapshotPayload snapshotPayload = tripRouteSnapshotService
                .loadSnapshotPayloadAndWarmRedis(tripId)
                .orElse(null);
        boolean snapshotHasMatched = snapshotPayload != null
                && snapshotPayload.getMatchedResults() != null
                && !snapshotPayload.getMatchedResults().isEmpty();

        TripRouteSnapshotPayload selectedPayload = snapshotHasMatched ? snapshotPayload : (latestHasMatched ? latestPayload : null);
        if (selectedPayload != null) {
            payload.matchedPolyline = buildMatchedPolylineForMap(selectedPayload.getMatchedResults(), displayCoordType);
            payload.reconstructedPolyline = buildReconstructedPolylineForMap(selectedPayload.getMatchedResults(), displayCoordType);
            payload.matchingDiagnostics = buildMatchingDiagnostics(selectedPayload);
            if (selectedPayload.getGeneratedAt() != null) {
                payload.routeGeneratedAt = DateTimeUtils.formatDateTime(new Date(selectedPayload.getGeneratedAt()));
            }
            payload.routeSource = snapshotHasMatched && selectedPayload == snapshotPayload ? "OSS_SNAPSHOT" : "LIVE_CACHE";
        }

        boolean fingerprintChanged = snapshotPayload == null
                || !Objects.equals(snapshotPayload.getFingerprint(), latestFingerprint);
        boolean needRefresh = trip.getStatus() == TripStatus.ACTIVE
                || trip.getStatus() == TripStatus.PAUSED
                || trip.getStatus() == TripStatus.PROCESSING
                || fingerprintChanged
                || !snapshotHasMatched;

        boolean refreshInProgress = isSnapshotRefreshInProgress(tripId);
        if (needRefresh) {
            if (!refreshInProgress) {
                refreshLatestSnapshotAsync(tripId);
            }
            payload.routeSyncStatus = selectedPayload == null
                    ? (latestHasMatched ? "PENDING" : "EMPTY")
                    : "REFRESHING";
        } else if (selectedPayload != null) {
            payload.routeSyncStatus = "FRESH";
        }

        return payload;
    }

    private Map<String, Object> buildMatchingDiagnostics(TripRouteSnapshotPayload payload) {
        if (payload == null || payload.getMatchedResults() == null || payload.getMatchedResults().isEmpty()) {
            return null;
        }

        List<MapMatchingResult> results = payload.getMatchedResults();
        int totalPoints = results.size();
        int roadMatchedPoints = 0;
        int rawRetainedPoints = 0;
        int highOffsetPoints = 0;
        double offsetSum = 0.0;
        List<Double> offsets = new ArrayList<>();
        Map<String, Integer> modeBreakdown = new LinkedHashMap<>();
        Map<String, Integer> reasonBreakdown = new LinkedHashMap<>();

        for (MapMatchingResult result : results) {
            String matchMode = StringUtils.hasText(result.getMatchMode()) ? result.getMatchMode().trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
            modeBreakdown.merge(matchMode, 1, Integer::sum);
            if ("RAW".equals(matchMode)) {
                rawRetainedPoints += 1;
            }
            if ("ROAD".equals(matchMode)) {
                roadMatchedPoints += 1;
            }

            if (StringUtils.hasText(result.getMatchReason())) {
                reasonBreakdown.merge(result.getMatchReason().trim().toUpperCase(Locale.ROOT), 1, Integer::sum);
            }

            double offset = result.getRoadDistanceMeters() == null ? Double.NaN : result.getRoadDistanceMeters();
            if (Double.isFinite(offset)) {
                offsets.add(offset);
                offsetSum += offset;
                if (offset >= 15.0) {
                    highOffsetPoints += 1;
                }
            }
        }

        Collections.sort(offsets);
        double matchedRate = totalPoints == 0 ? 0.0 : roadMatchedPoints * 100.0 / totalPoints;
        double rawRate = totalPoints == 0 ? 0.0 : rawRetainedPoints * 100.0 / totalPoints;
        double avgOffset = offsets.isEmpty() ? 0.0 : offsetSum / offsets.size();

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("available", Boolean.TRUE);
        diagnostics.put("algoVersion", payload.getAlgoVersion());
        diagnostics.put("totalPoints", totalPoints);
        diagnostics.put("roadMatchedPoints", roadMatchedPoints);
        diagnostics.put("rawRetainedPoints", rawRetainedPoints);
        diagnostics.put("roadMatchedRate", roundMetric(matchedRate));
        diagnostics.put("rawRetainedRate", roundMetric(rawRate));
        diagnostics.put("avgRoadOffsetMeters", roundMetric(avgOffset));
        diagnostics.put("medianRoadOffsetMeters", roundMetric(percentile(offsets, 0.5)));
        diagnostics.put("p90RoadOffsetMeters", roundMetric(percentile(offsets, 0.9)));
        diagnostics.put("highOffsetPoints", highOffsetPoints);
        diagnostics.put("modeBreakdown", modeBreakdown);
        diagnostics.put("reasonBreakdown", reasonBreakdown);
        diagnostics.put("generatedAt", payload.getGeneratedAt() == null ? null : DateTimeUtils.formatDateTime(new Date(payload.getGeneratedAt())));
        return diagnostics;
    }

    private double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0.0;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.get(0);
        }
        double index = Math.max(0.0, Math.min(1.0, percentile)) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double weight = index - lower;
        return sortedValues.get(lower) * (1.0 - weight) + sortedValues.get(upper) * weight;
    }

    private double roundMetric(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private boolean isSnapshotRefreshInProgress(Long tripId) {
        return tripId != null && redisService.hasKey(SNAPSHOT_LOCK_KEY_PREFIX + tripId);
    }

    private boolean isSnapshotRefreshInProgressError(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return false;
        }
        return throwable.getMessage().contains("路线快照保存正在进行中")
                || throwable.getMessage().contains("璺嚎蹇収淇濆瓨姝ｅ湪杩涜涓紝");
    }

    private List<StoryBlockVO> buildLiveStoryBlocks(Trip trip) {
        List<StoryTimelineItem> timeline = new ArrayList<>();
        Long tripId = trip.getId();

        if (trip.getStartTime() != null) {
            timeline.add(new StoryTimelineItem(
                    trip.getStartTime(),
                    0,
                    StoryBlockVO.builder()
                            .id("trip-start-" + tripId)
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.TEXT)
                            .sortTime(DateTimeUtils.formatDateTime(trip.getStartTime()))
                            .displayTimeText(DateTimeUtils.formatTime(trip.getStartTime()))
                            .title("行程开始")
                            .text(buildDefaultStoryLive(trip))
                            .mediaList(Collections.emptyList())
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        for (PlaceSummary place : placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId)) {
            PlaceSummaryVO relatedPlace = toPlaceSummaryVO(place);
            Date sortTime = place.getStartTime() != null ? place.getStartTime() : place.getGeneratedAt();
            timeline.add(new StoryTimelineItem(
                    sortTime,
                    10,
                    StoryBlockVO.builder()
                            .id("place-" + place.getId())
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.PLACE_SUMMARY)
                            .sortTime(DateTimeUtils.formatDateTime(sortTime))
                            .displayTimeText(DateTimeUtils.formatTime(sortTime))
                            .locationName(relatedPlace.getPoiName())
                            .point(relatedPlace.getCenterPoint())
                            .privacyMode(toPrivacyModeVO(place.getPrivacyLevel()))
                            .title(relatedPlace.getPoiName())
                            .text(String.format(
                                    Locale.ROOT,
                                    "%s 停留 %s，记录了 %d 张照片和 %d 个视频。",
                                    relatedPlace.getPoiName() == null ? "地点" : relatedPlace.getPoiName(),
                                    relatedPlace.getDurationText() == null ? "0 分钟" : relatedPlace.getDurationText(),
                                    defaultInteger(place.getPhotoCount()),
                                    defaultInteger(place.getVideoCount())
                            ))
                            .coverMedia(place.getPhotoCoverId() != null
                                    ? photoRepository.findById(place.getPhotoCoverId()).map(this::toPhotoMediaVO).orElse(null)
                                    : null)
                            .mediaList(Collections.emptyList())
                            .placeId(relatedPlace.getId())
                            .relatedPlace(relatedPlace)
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        for (Photo photo : photoRepository.findByTripId(tripId)) {
            if (photo.getNoteId() != null) {
                continue;
            }
            MediaAssetVO media = toPhotoMediaVO(photo);
            Date sortTime = photo.getShotTimeExif() != null ? photo.getShotTimeExif() : photo.getCreatedAt();
            timeline.add(new StoryTimelineItem(
                    sortTime,
                    20,
                    StoryBlockVO.builder()
                            .id("photo-" + photo.getId())
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.PHOTO)
                            .sortTime(DateTimeUtils.formatDateTime(sortTime))
                            .displayTimeText(DateTimeUtils.formatTime(sortTime))
                            .privacyMode(toPrivacyModeVO(photo.getPrivacyMode()))
                            .locationName(media != null ? media.getLocationName() : null)
                            .point(media != null ? media.getPoint() : null)
                            .title(photo.getUserCaption())
                            .text(photo.getUserCaption())
                            .coverMedia(media)
                            .mediaList(Collections.singletonList(media))
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        for (Video video : videoRepository.findByTripId(tripId)) {
            if (video.getNoteId() != null) {
                continue;
            }
            MediaAssetVO media = toVideoMediaVO(video);
            Date sortTime = video.getShotTimeExif() != null ? video.getShotTimeExif() : video.getCreatedAt();
            timeline.add(new StoryTimelineItem(
                    sortTime,
                    30,
                    StoryBlockVO.builder()
                            .id("video-" + video.getId())
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.VIDEO)
                            .sortTime(DateTimeUtils.formatDateTime(sortTime))
                            .displayTimeText(DateTimeUtils.formatTime(sortTime))
                            .privacyMode(toPrivacyModeVO(video.getPrivacyMode()))
                            .locationName(media != null ? media.getLocationName() : null)
                            .point(media != null ? media.getPoint() : null)
                            .title(video.getUserCaption())
                            .text(video.getUserCaption())
                            .coverMedia(media)
                            .mediaList(Collections.singletonList(media))
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        for (TripNote note : tripNoteRepository.findByTripIdOrderByCreatedAtDesc(tripId)) {
            Date sortTime = note.getAnchorTs() != null ? new Date(note.getAnchorTs()) : note.getCreatedAt();
            List<MediaAssetVO> noteMediaList = new ArrayList<>();
            for (Photo photo : photoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
                noteMediaList.add(toPhotoMediaVO(photo));
            }
            for (Video video : videoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
                noteMediaList.add(toVideoMediaVO(video));
            }
            noteMediaList.sort(Comparator.comparing(this::resolveStoryMediaSortTime, Comparator.nullsLast(String::compareTo)));
            timeline.add(new StoryTimelineItem(
                    sortTime,
                    40,
                    StoryBlockVO.builder()
                            .id("note-" + note.getId())
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.NOTE)
                            .sortTime(DateTimeUtils.formatDateTime(sortTime))
                            .displayTimeText(DateTimeUtils.formatTime(sortTime))
                            .locationName(note.getLocationName())
                            .point(buildGeoPoint(note.getLatEnc(), note.getLngEnc(), null, null, CoordType.GCJ02, CoordTypeVO.GCJ02))
                            .privacyMode(toPrivacyModeVO(parsePrivacyModeOrDefault(note.getPrivacyMode(), PrivacyMode.PUBLIC)))
                            .title(note.getTitle())
                            .text(note.getContent())
                            .coverMedia(noteMediaList.isEmpty() ? null : noteMediaList.get(0))
                            .mediaList(noteMediaList)
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        if (trip.getEndTime() != null) {
            timeline.add(new StoryTimelineItem(
                    trip.getEndTime(),
                    90,
                    StoryBlockVO.builder()
                            .id("trip-end-" + tripId)
                            .tripId(tripId)
                            .type(StoryBlockTypeVO.TEXT)
                            .sortTime(DateTimeUtils.formatDateTime(trip.getEndTime()))
                            .displayTimeText(DateTimeUtils.formatTime(trip.getEndTime()))
                            .title("行程结束")
                            .text("这段旅程已完成。")
                            .mediaList(Collections.emptyList())
                            .moodTags(Collections.emptyList())
                            .build()
            ));
        }

        timeline.sort(Comparator
                .comparing(StoryTimelineItem::sortTime, Comparator.nullsLast(Date::compareTo))
                .thenComparingInt(StoryTimelineItem::order)
                .thenComparing(item -> item.block().getId(), Comparator.nullsLast(String::compareTo)));

        List<StoryBlockVO> blocks = timeline.stream().map(StoryTimelineItem::block).collect(Collectors.toList());
        return applyStoryOverrides(blocks, tripId);
    }

    private List<StoryBlockVO> applyStoryOverrides(List<StoryBlockVO> blocks, Long tripId) {
        if (blocks == null || blocks.isEmpty() || tripId == null) {
            return blocks == null ? Collections.emptyList() : blocks;
        }

        Map<String, StoryBlock> overrideMap = new HashMap<>();
        for (StoryBlock override : storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId)) {
            if (override == null || !StringUtils.hasText(override.getRefType()) || override.getRefId() == null) {
                continue;
            }
            overrideMap.put(new StoryRef(override.getRefType().trim().toUpperCase(Locale.ROOT), override.getRefId()).key(), override);
        }

        List<StoryBlockVO> resolved = new ArrayList<>();
        for (StoryBlockVO block : blocks) {
            StoryRef ref = resolveStoryRef(block == null ? null : block.getId(), tripId);
            if (block == null || ref == null) {
                if (block != null) {
                    resolved.add(block);
                }
                continue;
            }
            StoryBlock override = overrideMap.get(ref.key());
            if (override == null) {
                resolved.add(block);
                continue;
            }
            if (Boolean.TRUE.equals(override.getIsHidden())) {
                continue;
            }
            resolved.add(StoryBlockVO.builder()
                    .id(block.getId())
                    .tripId(block.getTripId())
                    .type(block.getType())
                    .sortTime(override.getSortTime() != null ? DateTimeUtils.formatDateTime(override.getSortTime()) : block.getSortTime())
                    .displayTimeText(override.getSortTime() != null ? DateTimeUtils.formatTime(override.getSortTime()) : block.getDisplayTimeText())
                    .locationName(block.getLocationName())
                    .point(block.getPoint())
                    .privacyMode(block.getPrivacyMode())
                    .title(StringUtils.hasText(override.getTitle()) ? override.getTitle() : block.getTitle())
                    .text(StringUtils.hasText(override.getTextContent()) ? override.getTextContent() : block.getText())
                    .coverMedia(block.getCoverMedia())
                    .mediaList(block.getMediaList())
                    .placeId(block.getPlaceId())
                    .relatedPlace(block.getRelatedPlace())
                    .moodTags(block.getMoodTags())
                    .build());
        }
        return resolved;
    }

    private StoryRef resolveStoryRef(String blockId, Long tripId) {
        if (!StringUtils.hasText(blockId)) {
            return null;
        }
        if (blockId.startsWith("trip-start-")) {
            return new StoryRef("TRIP_START", tripId);
        }
        if (blockId.startsWith("trip-end-")) {
            return new StoryRef("TRIP_END", tripId);
        }
        if (blockId.startsWith("place-")) {
            return new StoryRef("PLACE_SUMMARY", parseStoryRefId(blockId.substring("place-".length())));
        }
        if (blockId.startsWith("photo-")) {
            return new StoryRef("PHOTO", parseStoryRefId(blockId.substring("photo-".length())));
        }
        if (blockId.startsWith("video-")) {
            return new StoryRef("VIDEO", parseStoryRefId(blockId.substring("video-".length())));
        }
        if (blockId.startsWith("note-")) {
            return new StoryRef("TRIP_NOTE", parseStoryRefId(blockId.substring("note-".length())));
        }
        return null;
    }

    private Long parseStoryRefId(String rawId) {
        if (!StringUtils.hasText(rawId)) {
            return null;
        }
        try {
            return Long.parseLong(rawId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record StoryRef(String refType, Long refId) {
        private String key() {
            return (refType == null ? "" : refType) + ":" + refId;
        }
    }

    private String resolveStoryMediaSortTime(MediaAssetVO media) {
        if (media == null) {
            return null;
        }
        if (StringUtils.hasText(media.getShotTime())) {
            return media.getShotTime();
        }
        return media.getCreatedAt();
    }

    private List<MapMarkerVO> buildMapMarkers(Long tripId, CoordTypeVO displayCoordType, List<TrackPoint> trackPoints) {
        List<MapMarkerVO> markers = new ArrayList<>();
        for (PlaceSummary place : placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId)) {
            GeoPointVO point = buildGeoPoint(place.getCenterLatEnc(), place.getCenterLngEnc(), null, null, CoordType.WGS84, displayCoordType);
            if (point == null) continue;
            markers.add(MapMarkerVO.builder()
                    .id("place-" + place.getId())
                    .type(null)
                    .point(point)
                    .title(place.getPoiName())
                    .subTitle(DateTimeUtils.formatDuration(normalizePlaceDuration(place.getDurationSec())))
                    .placeId(place.getId())
                    .calloutText(place.getPoiName())
                    .build());
        }
        return markers;
    }

    private TripDetailVO.TripSummaryVO buildTripSummary(Trip trip, int placeCount, TripAISummaryVO aiSummary) {
        TripMediaCounts mediaCounts = resolveTripMediaCounts(trip != null ? trip.getId() : null);
        return TripDetailVO.TripSummaryVO.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .status(toTripStatusVO(trip.getStatus()))
                .privacyMode(toPrivacyModeVO(trip.getPrivacyMode()))
                .summaryText(resolveTripSummaryText(trip, aiSummary))
                .cover(buildTripCoverMedia(trip.getId()))
                .startTime(DateTimeUtils.formatDateTime(trip.getStartTime()))
                .endTime(DateTimeUtils.formatDateTime(trip.getEndTime()))
                .distanceM(defaultLong(trip.getDistanceM()))
                .distanceText(formatDistance(trip.getDistanceM()))
                .durationSec(defaultLong(trip.getDurationSec()))
                .durationText(DateTimeUtils.formatDuration(trip.getDurationSec()))
                .photoCount(mediaCounts.photoCount())
                .videoCount(mediaCounts.videoCount())
                .placeCount(placeCount)
                .build();
    }

    private TripDetailVO.TripSummaryVO maskedTripSummary(TripDetailVO.TripSummaryVO summary, boolean privateMode) {
        if (summary == null) {
            return null;
        }
        String maskedText = privateMode
                ? "该行程未开启公开分享。"
                : "该行程以模糊模式分享，已隐藏具体地点、媒体和轨迹细节。";
        return TripDetailVO.TripSummaryVO.builder()
                .id(summary.getId())
                .title(summary.getTitle())
                .status(summary.getStatus())
                .privacyMode(summary.getPrivacyMode())
                .summaryText(maskedText)
                .cover(privateMode ? null : null)
                .startTime(summary.getStartTime())
                .endTime(summary.getEndTime())
                .distanceM(summary.getDistanceM())
                .distanceText(summary.getDistanceText())
                .durationSec(summary.getDurationSec())
                .durationText(summary.getDurationText())
                .photoCount(summary.getPhotoCount())
                .videoCount(summary.getVideoCount())
                .placeCount(summary.getPlaceCount())
                .build();
    }

    private String buildShareHint(PrivacyMode privacyMode) {
        PrivacyMode mode = privacyMode != null ? privacyMode : PrivacyMode.PUBLIC;
        return switch (mode) {
            case PRIVATE -> "当前行程为私密模式，不能对外分享。";
            case MASKED -> "当前行程以模糊模式分享，只展示基础概览信息。";
            case PUBLIC -> "当前行程可公开分享，好友无需登录即可查看。";
        };
    }

    private TripDetailVO.TripSummaryVO buildSharedTripSummary(TripDetailVO.TripSummaryVO summary, Trip trip) {
        if (summary == null) {
            return null;
        }
        Long tripId = trip == null ? null : trip.getId();
        int visiblePhotoCount = tripId == null ? 0 : (int) photoRepository.findByTripId(tripId).stream()
                .filter(photo -> !isPrivateMode(photo.getPrivacyMode()))
                .count();
        int visibleVideoCount = tripId == null ? 0 : (int) videoRepository.findByTripId(tripId).stream()
                .filter(video -> !isPrivateMode(video.getPrivacyMode()))
                .count();
        int visiblePlaceCount = tripId == null ? 0 : (int) placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId).stream()
                .filter(place -> !isPrivateMode(place.getPrivacyLevel()))
                .count();

        return TripDetailVO.TripSummaryVO.builder()
                .id(summary.getId())
                .title(summary.getTitle())
                .status(summary.getStatus())
                .privacyMode(summary.getPrivacyMode())
                .summaryText(summary.getSummaryText())
                .cover(resolveShareTripCover(tripId))
                .startTime(summary.getStartTime())
                .endTime(summary.getEndTime())
                .distanceM(summary.getDistanceM())
                .distanceText(summary.getDistanceText())
                .durationSec(summary.getDurationSec())
                .durationText(summary.getDurationText())
                .photoCount(visiblePhotoCount)
                .videoCount(visibleVideoCount)
                .placeCount(visiblePlaceCount)
                .build();
    }

    private MediaAssetVO resolveShareTripCover(Long tripId) {
        if (tripId == null) {
            return null;
        }
        for (Photo photo : photoRepository.findByTripIdAndIsCoverTrue(tripId)) {
            MediaAssetVO media = sanitizeShareMedia(toPhotoMediaVO(photo));
            if (media != null) {
                return media;
            }
        }
        for (Photo photo : photoRepository.findByTripId(tripId)) {
            MediaAssetVO media = sanitizeShareMedia(toPhotoMediaVO(photo));
            if (media != null) {
                return media;
            }
        }
        for (Video video : videoRepository.findByTripId(tripId)) {
            MediaAssetVO media = sanitizeShareMedia(toVideoMediaVO(video));
            if (media != null) {
                return media;
            }
        }
        return null;
    }

    private List<PlaceSummaryVO> buildSharePlaces(Long tripId) {
        if (tripId == null) {
            return Collections.emptyList();
        }
        return placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId).stream()
                .map(this::toPlaceSummaryVO)
                .map(this::sanitizeSharePlace)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<StoryBlockVO> buildShareStoryBlocks(Trip trip) {
        return buildLiveStoryBlocks(trip).stream()
                .map(this::sanitizeShareStoryBlock)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private PlaceSummaryVO sanitizeSharePlace(PlaceSummaryVO place) {
        if (place == null) {
            return null;
        }
        PrivacyMode mode = toPrivacyMode(place.getPrivacyLevel());
        if (mode == PrivacyMode.PRIVATE) {
            return null;
        }
        return PlaceSummaryVO.builder()
                .id(place.getId())
                .tripId(place.getTripId())
                .poiName(mode == PrivacyMode.MASKED ? "地点已隐藏" : place.getPoiName())
                .city(mode == PrivacyMode.MASKED ? null : place.getCity())
                .district(mode == PrivacyMode.MASKED ? null : place.getDistrict())
                .centerPoint(mode == PrivacyMode.MASKED ? null : place.getCenterPoint())
                .startTime(place.getStartTime())
                .endTime(place.getEndTime())
                .durationSec(place.getDurationSec())
                .durationText(place.getDurationText())
                .photoCount(place.getPhotoCount())
                .videoCount(place.getVideoCount())
                .coverMedia(sanitizeShareMedia(place.getCoverMedia()))
                .userNotes(mode == PrivacyMode.MASKED ? null : place.getUserNotes())
                .userTags(mode == PrivacyMode.MASKED ? Collections.emptyList() : place.getUserTags())
                .privacyLevel(place.getPrivacyLevel())
                .build();
    }

    private StoryBlockVO sanitizeShareStoryBlock(StoryBlockVO block) {
        if (block == null) {
            return null;
        }
        PrivacyMode mode = resolveShareStoryBlockPrivacy(block);
        if (mode == PrivacyMode.PRIVATE) {
            return null;
        }

        List<MediaAssetVO> mediaList = block.getMediaList() == null
                ? Collections.emptyList()
                : block.getMediaList().stream()
                .map(this::sanitizeShareMedia)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        MediaAssetVO coverMedia = sanitizeShareMedia(block.getCoverMedia());
        if (coverMedia == null && !mediaList.isEmpty()) {
            coverMedia = mediaList.get(0);
        }

        boolean maskLocation = mode == PrivacyMode.MASKED
                && (block.getType() == StoryBlockTypeVO.NOTE
                || block.getType() == StoryBlockTypeVO.PLACE_SUMMARY
                || block.getType() == StoryBlockTypeVO.PHOTO
                || block.getType() == StoryBlockTypeVO.VIDEO);
        String title = block.getType() == StoryBlockTypeVO.PLACE_SUMMARY && mode == PrivacyMode.MASKED
                ? "地点已隐藏"
                : block.getTitle();
        String text = block.getType() == StoryBlockTypeVO.PLACE_SUMMARY && mode == PrivacyMode.MASKED
                ? "该地点的具体位置信息已按隐私设置隐藏。"
                : block.getText();

        if ((block.getType() == StoryBlockTypeVO.PHOTO || block.getType() == StoryBlockTypeVO.VIDEO)
                && coverMedia == null && mediaList.isEmpty()) {
            return null;
        }

        return StoryBlockVO.builder()
                .id(block.getId())
                .tripId(block.getTripId())
                .type(block.getType())
                .sortTime(block.getSortTime())
                .displayTimeText(block.getDisplayTimeText())
                .locationName(maskLocation ? null : block.getLocationName())
                .point(maskLocation ? null : block.getPoint())
                .privacyMode(toPrivacyModeVO(mode))
                .title(title)
                .text(text)
                .coverMedia(coverMedia)
                .mediaList(mediaList)
                .placeId(block.getPlaceId())
                .relatedPlace(sanitizeSharePlace(block.getRelatedPlace()))
                .moodTags(block.getMoodTags())
                .build();
    }

    private PrivacyMode resolveShareStoryBlockPrivacy(StoryBlockVO block) {
        PrivacyMode fallback = toPrivacyMode(block == null ? null : block.getPrivacyMode());
        if (block == null) {
            return fallback;
        }
        StoryRef ref = resolveStoryRef(block.getId(), block.getTripId());
        if (ref == null || ref.refId() == null) {
            return fallback;
        }
        return switch (ref.refType()) {
            case "TRIP_NOTE" -> tripNoteRepository.findById(ref.refId())
                    .map(note -> parsePrivacyModeOrDefault(note.getPrivacyMode(), fallback))
                    .orElse(fallback);
            case "PHOTO" -> photoRepository.findById(ref.refId())
                    .map(photo -> photo.getPrivacyMode() != null ? photo.getPrivacyMode() : fallback)
                    .orElse(fallback);
            case "VIDEO" -> videoRepository.findById(ref.refId())
                    .map(video -> video.getPrivacyMode() != null ? video.getPrivacyMode() : fallback)
                    .orElse(fallback);
            default -> fallback;
        };
    }

    private MediaAssetVO sanitizeShareMedia(MediaAssetVO media) {
        if (media == null) {
            return null;
        }
        PrivacyMode mode = toPrivacyMode(media.getPrivacyMode());
        if (mode == PrivacyMode.PRIVATE) {
            return null;
        }
        return MediaAssetVO.builder()
                .id(media.getId())
                .type(media.getType())
                .tripId(media.getTripId())
                .url(media.getUrl())
                .thumbnailUrl(media.getThumbnailUrl())
                .shotTime(media.getShotTime())
                .createdAt(media.getCreatedAt())
                .durationSec(media.getDurationSec())
                .resolution(media.getResolution())
                .caption(media.getCaption())
                .privacyMode(media.getPrivacyMode())
                .shareMasked(mode == PrivacyMode.MASKED)
                .isCover(media.getIsCover())
                .point(mode == PrivacyMode.MASKED ? null : media.getPoint())
                .locationName(mode == PrivacyMode.MASKED ? null : media.getLocationName())
                .build();
    }

    private TripMapVO sanitizeShareMap(TripMapVO map) {
        if (map == null) {
            return null;
        }
        return TripMapVO.builder()
                .center(map.getCenter())
                .zoom(map.getZoom())
                .bbox(map.getBbox())
                .rawPolyline(map.getRawPolyline())
                .matchedPolyline(map.getMatchedPolyline())
                .reconstructedPolyline(map.getReconstructedPolyline())
                .markers(Collections.emptyList())
                .rawSegments(map.getRawSegments())
                .matchedSegments(map.getMatchedSegments())
                .reconstructedSegments(map.getReconstructedSegments())
                .mediaMarkers(Collections.emptyList())
                .matchingDiagnostics(map.getMatchingDiagnostics())
                .routeSource(map.getRouteSource())
                .routeSyncStatus(map.getRouteSyncStatus())
                .routeGeneratedAt(map.getRouteGeneratedAt())
                .build();
    }

    private PrivacyMode toPrivacyMode(PrivacyModeVO privacyModeVO) {
        if (privacyModeVO == null) {
            return PrivacyMode.PUBLIC;
        }
        try {
            return PrivacyMode.valueOf(privacyModeVO.name());
        } catch (Exception ignored) {
            return PrivacyMode.PUBLIC;
        }
    }

    private boolean isPrivateMode(PrivacyMode privacyMode) {
        return privacyMode == PrivacyMode.PRIVATE;
    }

    private String resolveTripSummaryText(Trip trip, TripAISummaryVO aiSummary) {
        if (aiSummary != null && StringUtils.hasText(aiSummary.getOverview())) {
            return sanitizeAiText(aiSummary.getOverview());
        }
        return resolveTripSummaryText(trip);
    }

    private String resolveTripSummaryText(Trip trip) {
        if (trip == null) {
            return null;
        }
        Optional<TripAiSummary> latestSummary = tripAiSummaryRepository
                .findFirstByTripIdAndIsLatestTrueOrderByGeneratedAtDescIdDesc(trip.getId());
        if (latestSummary.isPresent() && StringUtils.hasText(latestSummary.get().getOverview())) {
            return sanitizeAiText(latestSummary.get().getOverview());
        }
        if (StringUtils.hasText(trip.getSummaryText())) {
            return sanitizeAiText(trip.getSummaryText());
        }
        return null;
    }

    private MediaAssetVO buildTripCoverMedia(Long tripId) {
        if (tripId == null) {
            return null;
        }
        Photo coverPhoto = photoRepository.findByTripIdAndIsCoverTrue(tripId).stream().findFirst().orElse(null);
        if (coverPhoto == null) {
            coverPhoto = photoRepository.findFirstByTripIdOrderByCreatedAtDesc(tripId);
        }
        return coverPhoto != null ? toPhotoMediaVO(coverPhoto) : null;
    }

    private PlaceSummaryVO toPlaceSummaryVO(PlaceSummary place) {
        MediaAssetVO coverMedia = null;
        if (place.getPhotoCoverId() != null) {
            Photo photo = photoRepository.findById(place.getPhotoCoverId()).orElse(null);
            if (photo != null) {
                coverMedia = toPhotoMediaVO(photo);
            }
        } else if (place.getVideoCoverId() != null) {
            Video video = videoRepository.findById(place.getVideoCoverId()).orElse(null);
            if (video != null) {
                coverMedia = toVideoMediaVO(video);
            }
        }
        List<String> tags = place.getUserTags() == null || place.getUserTags().trim().isEmpty()
                ? Collections.emptyList()
                : Arrays.stream(place.getUserTags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        return PlaceSummaryVO.builder()
                .id(place.getId())
                .tripId(place.getTripId())
                .poiName(place.getPoiName())
                .city(place.getCity())
                .district(place.getDistrict())
                .centerPoint(buildGeoPoint(place.getCenterLatEnc(), place.getCenterLngEnc(), null, null, CoordType.WGS84, CoordTypeVO.GCJ02))
                .startTime(DateTimeUtils.formatDateTime(place.getStartTime()))
                .endTime(DateTimeUtils.formatDateTime(place.getEndTime()))
                .durationSec(normalizePlaceDuration(place.getDurationSec()))
                .durationText(DateTimeUtils.formatDuration(normalizePlaceDuration(place.getDurationSec())))
                .photoCount(defaultInteger(place.getPhotoCount()))
                .videoCount(defaultInteger(place.getVideoCount()))
                .coverMedia(coverMedia)
                .userNotes(place.getUserNotes())
                .userTags(tags)
                .privacyLevel(toPrivacyModeVO(place.getPrivacyLevel()))
                .build();
    }

    private StoryBlockVO toStoryBlockVO(StoryBlock block) {
        PlaceSummaryVO relatedPlace = null;
        if ("PLACE_SUMMARY".equalsIgnoreCase(block.getRefType()) && block.getRefId() != null) {
            relatedPlace = placeSummaryRepository.findById(block.getRefId()).map(this::toPlaceSummaryVO).orElse(null);
        }
        StoryBlockTypeVO blockType = toStoryBlockTypeVO(block.getBlockType());
        if ("TRIP_NOTE".equalsIgnoreCase(block.getRefType())) {
            blockType = StoryBlockTypeVO.NOTE;
        }
        return StoryBlockVO.builder()
                .id(block.getId() != null ? String.valueOf(block.getId()) : null)
                .tripId(block.getTripId())
                .type(blockType)
                .sortTime(DateTimeUtils.formatDateTime(block.getSortTime()))
                .displayTimeText(DateTimeUtils.formatTime(block.getSortTime()))
                .locationName(relatedPlace != null ? relatedPlace.getPoiName() : null)
                .point(relatedPlace != null ? relatedPlace.getCenterPoint() : null)
                .privacyMode(relatedPlace != null ? relatedPlace.getPrivacyLevel() : null)
                .title(block.getTitle())
                .text(block.getTextContent())
                .coverMedia(buildCoverMedia(block))
                .mediaList(Collections.emptyList())
                .placeId(relatedPlace != null ? relatedPlace.getId() : null)
                .relatedPlace(relatedPlace)
                .moodTags(Collections.emptyList())
                .build();
    }

    private MediaAssetVO buildCoverMedia(StoryBlock block) {
        if (block == null) return null;
        if ("PHOTO".equalsIgnoreCase(block.getRefType()) && block.getRefId() != null) {
            return photoRepository.findById(block.getRefId()).map(this::toPhotoMediaVO).orElse(null);
        }
        if ("VIDEO".equalsIgnoreCase(block.getRefType()) && block.getRefId() != null) {
            return videoRepository.findById(block.getRefId()).map(this::toVideoMediaVO).orElse(null);
        }
        if (block.getCoverObjectKey() == null || block.getCoverObjectKey().trim().isEmpty()) return null;
        return MediaAssetVO.builder()
                .id(block.getRefId())
                .tripId(block.getTripId())
                .url(block.getCoverObjectKey())
                .thumbnailUrl(block.getCoverObjectKey())
                .createdAt(DateTimeUtils.formatDateTime(block.getSortTime()))
                .type(inferMediaType(block.getBlockType()))
                .locationName(block.getTitle())
                .build();
    }

    private MediaAssetVO toPhotoMediaVO(Photo photo) {
        byte[] latBytes = resolvePhotoLocationLatBytes(photo);
        byte[] lngBytes = resolvePhotoLocationLngBytes(photo);
        return MediaAssetVO.builder()
                .id(photo.getId())
                .tripId(photo.getTripId())
                .type(MediaTypeVO.PHOTO)
                .url(photo.getObjectKey())
                .thumbnailUrl(photo.getObjectKey())
                .shotTime(DateTimeUtils.formatDateTime(photo.getShotTimeExif()))
                .createdAt(DateTimeUtils.formatDateTime(photo.getCreatedAt()))
                .caption(photo.getUserCaption())
                .privacyMode(toPrivacyModeVO(photo.getPrivacyMode()))
                .shareMasked(photo.getPrivacyMode() == PrivacyMode.MASKED)
                .isCover(Boolean.TRUE.equals(photo.getIsCover()))
                .point(latBytes != null && lngBytes != null ? buildGeoPoint(latBytes, lngBytes, null, null, resolvePhotoCoordType(photo), CoordTypeVO.GCJ02) : null)
                .locationName(photo.getLocationName())
                .build();
    }

    private MediaAssetVO toVideoMediaVO(Video video) {
        byte[] latBytes = resolveVideoLocationLatBytes(video);
        byte[] lngBytes = resolveVideoLocationLngBytes(video);
        return MediaAssetVO.builder()
                .id(video.getId())
                .tripId(video.getTripId())
                .type(MediaTypeVO.VIDEO)
                .url(video.getObjectKey())
                .thumbnailUrl(video.getThumbnailObjectKey())
                .shotTime(DateTimeUtils.formatDateTime(video.getShotTimeExif()))
                .createdAt(DateTimeUtils.formatDateTime(video.getCreatedAt()))
                .durationSec(video.getDurationSec())
                .resolution(video.getResolution())
                .caption(video.getUserCaption())
                .privacyMode(toPrivacyModeVO(video.getPrivacyMode()))
                .shareMasked(video.getPrivacyMode() == PrivacyMode.MASKED)
                .point(latBytes != null && lngBytes != null ? buildGeoPoint(latBytes, lngBytes, null, null, resolveVideoCoordType(video), CoordTypeVO.GCJ02) : null)
                .locationName(video.getLocationName())
                .build();
    }

    private byte[] resolvePhotoLocationLatBytes(Photo photo) {
        if (photo == null || shouldIgnorePhotoLocation(photo)) {
            return null;
        }
        return photo.getCaptureLatOverride() != null ? photo.getCaptureLatOverride() : photo.getLatEnc();
    }

    private byte[] resolvePhotoLocationLngBytes(Photo photo) {
        if (photo == null || shouldIgnorePhotoLocation(photo)) {
            return null;
        }
        return photo.getCaptureLngOverride() != null ? photo.getCaptureLngOverride() : photo.getLngEnc();
    }

    private byte[] resolveVideoLocationLatBytes(Video video) {
        if (video == null || shouldIgnoreVideoLocation(video)) {
            return null;
        }
        return video.getCaptureLatOverride() != null ? video.getCaptureLatOverride() : video.getLatEnc();
    }

    private byte[] resolveVideoLocationLngBytes(Video video) {
        if (video == null || shouldIgnoreVideoLocation(video)) {
            return null;
        }
        return video.getCaptureLngOverride() != null ? video.getCaptureLngOverride() : video.getLngEnc();
    }

    private boolean shouldIgnorePhotoLocation(Photo photo) {
        return photo != null
                && "NONE".equalsIgnoreCase(photo.getCaptureCoordSource())
                && photo.getCaptureLatOverride() == null
                && photo.getCaptureLngOverride() == null;
    }

    private boolean shouldIgnoreVideoLocation(Video video) {
        return video != null
                && "NONE".equalsIgnoreCase(video.getCaptureCoordSource())
                && video.getCaptureLatOverride() == null
                && video.getCaptureLngOverride() == null;
    }

    private CoordType resolvePhotoCoordType(Photo photo) {
        if (photo == null || shouldIgnorePhotoLocation(photo)) {
            return null;
        }
        if ("WGS84".equalsIgnoreCase(photo.getCaptureCoordType())) {
            return CoordType.WGS84;
        }
        if ("GCJ02".equalsIgnoreCase(photo.getCaptureCoordType())) {
            return CoordType.GCJ02;
        }
        if ("EXIF".equalsIgnoreCase(photo.getCaptureCoordSource())) {
            return CoordType.WGS84;
        }
        if (photo.getCaptureLatOverride() != null || photo.getCaptureLngOverride() != null) {
            return CoordType.GCJ02;
        }
        return CoordType.GCJ02;
    }

    private CoordType resolveVideoCoordType(Video video) {
        if (video == null || shouldIgnoreVideoLocation(video)) {
            return null;
        }
        if ("WGS84".equalsIgnoreCase(video.getCaptureCoordType())) {
            return CoordType.WGS84;
        }
        if ("GCJ02".equalsIgnoreCase(video.getCaptureCoordType())) {
            return CoordType.GCJ02;
        }
        if ("EXIF".equalsIgnoreCase(video.getCaptureCoordSource())) {
            return CoordType.WGS84;
        }
        if (video.getCaptureLatOverride() != null || video.getCaptureLngOverride() != null) {
            return CoordType.GCJ02;
        }
        return CoordType.GCJ02;
    }

    private MediaTypeVO inferMediaType(BlockType blockType) {
        if (blockType == null) return null;
        String name = blockType.name();
        if (name.contains("VIDEO")) return MediaTypeVO.VIDEO;
        if (name.contains("PHOTO")) return MediaTypeVO.PHOTO;
        return null;
    }

    private TripAISummaryVO toTripAiSummaryVO(Long tripId, Map<String, Object> summary) {
        List<String> highlights = Collections.emptyList();
        Object highlightsObj = summary.get("highlights");
        if (highlightsObj instanceof List<?> list) {
            highlights = list.stream()
                    .map(String::valueOf)
                    .map(this::sanitizeAiListItem)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        return TripAISummaryVO.builder()
                .tripId(tripId)
                .overview(sanitizeAiText(asString(summary.get("overview"))))
                .highlights(highlights)
                .routeSummary(sanitizeAiText(asString(summary.get("routeSummary"))))
                .bestMoment(sanitizeAiText(asString(summary.get("bestMoment"))))
                .generatedAt(DateTimeUtils.formatGeneratedAt(summary.get("generatedAt")))
                .version(asString(summary.get("version")))
                .build();
    }

    private List<String> parseHighlights(String highlightsText) {
        if (highlightsText == null || highlightsText.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(highlightsText.split("\\r?\\n"))
                .map(this::sanitizeAiListItem)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private String sanitizeAiListItem(String text) {
        String sanitized = sanitizeAiText(text);
        if (!StringUtils.hasText(sanitized)) {
            return null;
        }
        return sanitized.replaceFirst("^[-*•#\\s]+", "").trim();
    }

    private String sanitizeAiText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String sanitized = text
                .replace("\r", "")
                .replace("***", "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("\\*\\s*\\*", "")
                .replaceAll("_{2,}", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s*", "")
                .replaceAll("(?m)^\\s*[-*+•]\\s+", "")
                .trim();
        return sanitized.replaceAll("\\n{3,}", "\n\n");
    }

    long calculateTotalDistance(List<TrackPoint> trackPoints) {
        if (trackPoints.size() < 2) return 0L;
        double total = 0.0;
        for (int i = 1; i < trackPoints.size(); i++) {
            total += calculateDistance(trackPoints.get(i - 1).getLatEnc(), trackPoints.get(i - 1).getLngEnc(), trackPoints.get(i).getLatEnc(), trackPoints.get(i).getLngEnc());
        }
        return Math.round(total);
    }

    private double calculateDistance(byte[] lat1Enc, byte[] lng1Enc, byte[] lat2Enc, byte[] lng2Enc) {
        double lat1 = bytesToDouble(lat1Enc), lng1 = bytesToDouble(lng1Enc), lat2 = bytesToDouble(lat2Enc), lng2 = bytesToDouble(lng2Enc);
        if (!isValidCoordinate(lat1, lng1) || !isValidCoordinate(lat2, lng2)) return 0.0;
        double rLat1 = Math.toRadians(lat1), rLon1 = Math.toRadians(lng1), rLat2 = Math.toRadians(lat2), rLon2 = Math.toRadians(lng2);
        double dLat = rLat2 - rLat1, dLon = rLon2 - rLon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public void calculateAndSaveBBox(Long tripId, List<TrackPoint> trackPoints) {
        if (trackPoints.isEmpty()) return;
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (TrackPoint point : trackPoints) {
            double lat = bytesToDouble(point.getLatEnc()), lng = bytesToDouble(point.getLngEnc());
            if (!isValidCoordinate(lat, lng)) continue;
            minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat); minLng = Math.min(minLng, lng); maxLng = Math.max(maxLng, lng);
        }
        if (minLat == Double.MAX_VALUE) return;
        TripBBox bbox = new TripBBox();
        bbox.setTripId(tripId); bbox.setMinLat((float) minLat); bbox.setMaxLat((float) maxLat); bbox.setMinLng((float) minLng); bbox.setMaxLng((float) maxLng);
        tripBBoxRepository.save(bbox);
    }

    private void generatePlaceSummaries(Long tripId) {
        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        if (trackPoints.size() < 10) {
            return;
        }

        placeSummaryRepository.deleteByTripId(tripId);

        int chunkSize = 10;
        String fallbackCity = "未知";
        String fallbackDistrict = "";

        for (int i = 0; i < trackPoints.size(); i += chunkSize) {
            List<TrackPoint> chunk = trackPoints.subList(i, Math.min(i + chunkSize, trackPoints.size()));

            PlaceSummary placeSummary = new PlaceSummary();
            placeSummary.setUserId(trackPoints.get(0).getUserId());
            placeSummary.setTripId(tripId);

            double avgLat = 0.0;
            double avgLng = 0.0;
            for (TrackPoint point : chunk) {
                avgLat += bytesToDouble(point.getLatEnc());
                avgLng += bytesToDouble(point.getLngEnc());
            }
            avgLat /= chunk.size();
            avgLng /= chunk.size();

            placeSummary.setCenterLatEnc(doubleToBytes(avgLat));
            placeSummary.setCenterLngEnc(doubleToBytes(avgLng));

            placeSummary.setStartTime(new Date(chunk.get(0).getTs()));
            placeSummary.setEndTime(new Date(chunk.get(chunk.size() - 1).getTs()));
            placeSummary.setDurationSec(
                    (placeSummary.getEndTime().getTime() - placeSummary.getStartTime().getTime()) / 1000L
            );

            placeSummary.setPoiName("地点 " + (i / chunkSize + 1));

            // 先强制兜底，避免 city 非空约束导致整个行程收口失败
            placeSummary.setCity(fallbackCity);
            placeSummary.setDistrict(fallbackDistrict);

            placeSummary.setPhotoCount(0);
            placeSummary.setVideoCount(0);
            placeSummary.setPrivacyLevel(PrivacyMode.PUBLIC);
            placeSummary.setGeneratedAt(new Date());
            placeSummary.setCreatedAt(new Date());
            placeSummary.setUpdatedAt(new Date());

            placeSummaryRepository.save(placeSummary);
        }
    }

    private String buildDefaultStory(Trip trip) {
        TripMediaCounts mediaCounts = resolveTripMediaCounts(trip != null ? trip.getId() : null);
        StringBuilder story = new StringBuilder();
        story.append("这是一次").append(trip.getTitle()).append("的旅程");
        if (defaultLong(trip.getDistanceM()) > 0) story.append("，总行程").append(formatDistance(trip.getDistanceM()));
        if (defaultLong(trip.getDurationSec()) > 0) story.append("，耗时").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
        story.append("。旅途中留下了").append(defaultInteger(trip.getPhotoCount())).append("张照片和").append(defaultInteger(trip.getVideoCount())).append("个视频，记录了美好的瞬间。");
        return story.toString();
    }

    private List<String> buildDefaultHighlights(Trip trip) {
        List<String> highlights = new ArrayList<>();
        if (defaultInteger(trip.getPhotoCount()) > 0) highlights.add("拍摄了 " + trip.getPhotoCount() + " 张照片");
        if (defaultInteger(trip.getVideoCount()) > 0) highlights.add("录制了 " + trip.getVideoCount() + " 个视频");
        if (defaultLong(trip.getDistanceM()) > 1000) highlights.add("总行程达 " + formatDistance(trip.getDistanceM()));
        return highlights;
    }

    private String buildDefaultStoryLive(Trip trip) {
        TripMediaCounts mediaCounts = resolveTripMediaCounts(trip != null ? trip.getId() : null);
        StringBuilder story = new StringBuilder();
        story.append("这是一次").append(trip.getTitle()).append("的旅程");
        if (defaultLong(trip.getDistanceM()) > 0) {
            story.append("，总行程 ").append(formatDistance(trip.getDistanceM()));
        }
        if (defaultLong(trip.getDurationSec()) > 0) {
            story.append("，耗时 ").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
        }
        story.append("。旅途中留下了")
                .append(mediaCounts.photoCount())
                .append(" 张照片和 ")
                .append(mediaCounts.videoCount())
                .append(" 个视频，记录下沿途的瞬间。");
        return story.toString();
    }

    private List<String> buildDefaultHighlightsLive(Trip trip) {
        TripMediaCounts mediaCounts = resolveTripMediaCounts(trip != null ? trip.getId() : null);
        List<String> highlights = new ArrayList<>();
        if (mediaCounts.photoCount() > 0) {
            highlights.add("拍摄了 " + mediaCounts.photoCount() + " 张照片");
        }
        if (mediaCounts.videoCount() > 0) {
            highlights.add("录制了 " + mediaCounts.videoCount() + " 个视频");
        }
        if (defaultLong(trip.getDistanceM()) > 1000) {
            highlights.add("总行程达到 " + formatDistance(trip.getDistanceM()));
        }
        return highlights;
    }

    private void performMapMatching(Long tripId) {
        try {
            log.info("[TRIP_MAP_MATCH] tripId={} start", tripId);
            List<MapMatchingResult> matchedResults = trackPointService.matchTrajectory(tripId);
            logMatchedRouteSummary(tripId, matchedResults);
            log.info("[TRIP_MAP_MATCH] tripId={} done resultCount={}", tripId, matchedResults == null ? 0 : matchedResults.size());
        } catch (Exception e) {
            log.warn("[TRIP_MAP_MATCH] tripId={} failed: {}", tripId, e.getMessage(), e);
        }
    }

    private void logMatchedRouteSummary(Long tripId, List<MapMatchingResult> matchedResults) {
        if (matchedResults == null || matchedResults.isEmpty()) {
            log.info("[TRIP_MATCH_ROUTE] tripId={} route=EMPTY", tripId);
            return;
        }
        List<String> chain = new ArrayList<>();
        Long lastRoadId = null;
        for (MapMatchingResult result : matchedResults) {
            if (result.getMatchedRoadId() == null) {
                continue;
            }
            if (Objects.equals(lastRoadId, result.getMatchedRoadId())) {
                continue;
            }
            chain.add("seg=" + result.getMatchedRoadId() + "/name=" + (result.getMatchedRoadName() == null ? "-" : result.getMatchedRoadName()));
            lastRoadId = result.getMatchedRoadId();
        }
        log.info("[TRIP_MATCH_ROUTE] tripId={} uniqueRoadSegments={} chain={}", tripId, chain.size(), chain);
    }

    private boolean isEmptyPolyline(TrackPolylineVO polyline) {
        return polyline == null || polyline.getPoints() == null || polyline.getPoints().isEmpty();
    }

    private void triggerFinalizeTripAsync(Long tripId) {
        if (tripId == null) {
            return;
        }
        if (!FINALIZING_TRIP_IDS.add(tripId)) {
            log.info("[TRIP_FINALIZE_ASYNC] tripId={} finalize already running, skip duplicate trigger", tripId);
            return;
        }
        CompletableFuture.runAsync(() -> {
            runFinalizeTrip(tripId);
        });
    }

    private void runFinalizeTrip(Long tripId) {
        try {
            settleTrip(tripId);
        } catch (Exception e) {
            log.error("[TRIP_FINALIZE_ASYNC] tripId={} failed: {}", tripId, e.getMessage(), e);
            tripRepository.findById(tripId).ifPresent(t -> {
                if (t.getStatus() == TripStatus.PROCESSING) {
                    t.setStatus(TripStatus.FAILED);
                    t.setUpdatedAt(new Date());
                    tripRepository.save(t);
                }
            });
        } finally {
            FINALIZING_TRIP_IDS.remove(tripId);
        }
    }

    private void recoverProcessingTripIfStuck(Trip trip) {
        if (trip == null || trip.getStatus() != TripStatus.PROCESSING) {
            return;
        }
        Date lastUpdatedAt = trip.getUpdatedAt();
        long timeoutMs = processingRetryTimeoutMs > 0 ? processingRetryTimeoutMs : DEFAULT_PROCESSING_RETRY_TIMEOUT_MS;
        if (lastUpdatedAt != null && System.currentTimeMillis() - lastUpdatedAt.getTime() < timeoutMs) {
            return;
        }
        trip.setUpdatedAt(new Date());
        tripRepository.save(trip);
        log.warn("[TRIP_PROCESSING_RECOVER] tripId={} retry finalize after stale processing", trip.getId());
        triggerFinalizeTripAsync(trip.getId());
    }

    private TripMapVO.BBoxVO buildBBox(Long tripId, List<TrackPoint> trackPoints, CoordTypeVO displayCoordType) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return TripMapVO.BBoxVO.builder().minLat(0.0).minLng(0.0).maxLat(0.0).maxLng(0.0).build();
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (TrackPoint point : trackPoints) {
            double lat = bytesToDouble(point.getLatEnc()), lng = bytesToDouble(point.getLngEnc());
            if (!isValidCoordinate(lat, lng)) continue;
            double[] display = toDisplayCoord(lat, lng, point.getRawCoordType(), displayCoordType);
            minLat = Math.min(minLat, display[0]); maxLat = Math.max(maxLat, display[0]); minLng = Math.min(minLng, display[1]); maxLng = Math.max(maxLng, display[1]);
        }
        if (minLat == Double.MAX_VALUE) minLat = maxLat = minLng = maxLng = 0.0;
        return TripMapVO.BBoxVO.builder().minLat(minLat).minLng(minLng).maxLat(maxLat).maxLng(maxLng).build();
    }

    private GeoPointVO buildCenter(TripMapVO.BBoxVO bbox, CoordTypeVO displayCoordType) {
        return GeoPointVO.builder()
                .lat((defaultDouble(bbox.getMinLat()) + defaultDouble(bbox.getMaxLat())) / 2.0)
                .lng((defaultDouble(bbox.getMinLng()) + defaultDouble(bbox.getMaxLng())) / 2.0)
                .coordType(displayCoordType)
                .build();
    }

    private Integer resolveZoom(TripMapVO.BBoxVO bbox) {
        double span = Math.max(Math.abs(defaultDouble(bbox.getMaxLat()) - defaultDouble(bbox.getMinLat())), Math.abs(defaultDouble(bbox.getMaxLng()) - defaultDouble(bbox.getMinLng())));
        if (span < 0.002) return 17; if (span < 0.005) return 16; if (span < 0.01) return 15; if (span < 0.03) return 14; if (span < 0.08) return 13; return 12;
    }

    private List<Map<String, Object>> toOriginalPointMaps(List<TrackPoint> trackPoints) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (TrackPoint point : trackPoints) {
            double lat = bytesToDouble(point.getLatEnc()), lng = bytesToDouble(point.getLngEnc());
            if (!isValidCoordinate(lat, lng)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lat", lat);
            item.put("lng", lng);
            item.put("ts", point.getTs());
            item.put("accuracyM", point.getAccuracyM());
            item.put("speedMps", point.getSpeedMps());
            item.put("headingDeg", point.getHeadingDeg());
            if (point.getRawCoordType() != null) {
                item.put("coordType", point.getRawCoordType().name());
            } else {
                item.put("coordType", "WGS84");
            }
            points.add(item);
        }
        return points;
    }
    private List<TrackPoint> getEffectiveTrackPoints(Long tripId) {
        List<TrackPoint> points = trackPointRepository.findByTripIdAndRenderEligibleOrderByTsAsc(tripId, true);
        return points == null ? Collections.emptyList() : points;
    }
    private void openFirstSegmentIfAbsent(Trip trip) {
        if (trip == null || trip.getId() == null) {
            return;
        }
        if (tripSegmentRepository.findTopByTripIdOrderBySegmentNoDesc(trip.getId()).isPresent()) {
            return;
        }

        TripSegment segment = new TripSegment();
        segment.setTripId(trip.getId());
        segment.setSegmentNo(1);
        segment.setStartTs(trip.getStartTime() == null
                ? System.currentTimeMillis()
                : trip.getStartTime().getTime());
        segment.setStartReason("TRIP_START");
        segment.setIsClosed(false);
        segment.setCreatedAt(new Date());
        segment.setUpdatedAt(new Date());
        tripSegmentRepository.save(segment);
    }

    private TripSegment openNextSegment(Long tripId, long startTs, String reason) {
        TripSegment existingOpen = tripSegmentRepository
                .findTopByTripIdAndIsClosedFalseOrderBySegmentNoDesc(tripId)
                .orElse(null);
        if (existingOpen != null) {
            return existingOpen;
        }

        int nextNo = tripSegmentRepository.findTopByTripIdOrderBySegmentNoDesc(tripId)
                .map(s -> s.getSegmentNo() + 1)
                .orElse(1);

        TripSegment segment = new TripSegment();
        segment.setTripId(tripId);
        segment.setSegmentNo(nextNo);
        segment.setStartTs(startTs);
        segment.setStartReason(reason);
        segment.setIsClosed(false);
        segment.setCreatedAt(new Date());
        segment.setUpdatedAt(new Date());
        return tripSegmentRepository.save(segment);
    }

    private void closeOpenSegment(Long tripId, long endTs, String reason) {
        tripSegmentRepository.findTopByTripIdAndIsClosedFalseOrderBySegmentNoDesc(tripId)
                .ifPresent(segment -> {
                    segment.setEndTs(endTs);
                    segment.setEndReason(reason);
                    segment.setIsClosed(true);
                    segment.setUpdatedAt(new Date());
                    tripSegmentRepository.save(segment);
                });
    }

    private Long resolveBatchStartTs(List<Map<String, Object>> points) {
        Long minTs = null;
        if (points == null) {
            return null;
        }
        for (Map<String, Object> point : points) {
            Long ts = toLong(point.get("ts"));
            if (ts == null) {
                continue;
            }
            if (minTs == null || ts < minTs) {
                minTs = ts;
            }
        }
        return minTs;
    }

    private TrackPolylineVO emptyPolyline() {
        return TrackPolylineVO.builder().points(Collections.emptyList()).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private TrackPolylineVO mergeTrackPolylineList(List<TrackPolylineVO> segments) {
        if (segments == null || segments.isEmpty()) return emptyPolyline();
        List<GeoPointVO> allPoints = new ArrayList<>();
        for (TrackPolylineVO seg : segments) {
            if (seg != null && seg.getPoints() != null) {
                allPoints.addAll(seg.getPoints());
            }
        }
        return TrackPolylineVO.builder().points(allPoints).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private List<MapMarkerVO> buildMediaMarkersForMap(Long tripId, CoordTypeVO displayCoordType) {
        List<MapMarkerVO> markers = new ArrayList<>();
        List<Anchor> anchors = anchorRepository.findByTripIdOrderByMatchedTsAsc(tripId);
        Map<Long, Photo> photoMap = photoRepository.findByTripId(tripId).stream()
                .collect(Collectors.toMap(Photo::getId, photo -> photo, (left, right) -> left));
        Map<Long, Video> videoMap = videoRepository.findByTripId(tripId).stream()
                .collect(Collectors.toMap(Video::getId, video -> video, (left, right) -> left));
        for (Anchor anchor : anchors) {
            if (anchor.getLatEnc() == null || anchor.getLngEnc() == null) continue;
            double lat = bytesToDouble(anchor.getLatEnc());
            double lng = bytesToDouble(anchor.getLngEnc());
            if (!isValidCoordinate(lat, lng)) continue;
            double[] display = toDisplayCoord(lat, lng, CoordType.WGS84, displayCoordType);
            String title = "";
            String coverUrl = null;
            if (anchor.getPhotoId() != null) title = "照片";
            else if (anchor.getVideoId() != null) title = "视频";
            if (anchor.getPhotoId() != null) {
                Photo photo = photoMap.get(anchor.getPhotoId());
                if (photo != null) {
                    coverUrl = photo.getObjectKey();
                }
            } else if (anchor.getVideoId() != null) {
                Video video = videoMap.get(anchor.getVideoId());
                if (video != null) {
                    coverUrl = StringUtils.hasText(video.getThumbnailObjectKey()) ? video.getThumbnailObjectKey() : video.getObjectKey();
                }
            }
            markers.add(MapMarkerVO.builder()
                    .id("media-" + anchor.getId())
                    .type(MarkerTypeVO.MEDIA)
                    .point(GeoPointVO.builder().lat(display[0]).lng(display[1]).coordType(displayCoordType).build())
                    .title(title)
                    .iconUrl(coverUrl)
                    .coverUrl(coverUrl)
                    .mediaId(anchor.getPhotoId() != null ? anchor.getPhotoId() : anchor.getVideoId())
                    .build());
        }
        return markers;
    }

    private GeoPointVO buildGeoPoint(byte[] latEnc, byte[] lngEnc, Float accuracyM, Long ts, CoordType sourceCoordType, CoordTypeVO displayCoordType) {
        double lat = bytesToDouble(latEnc), lng = bytesToDouble(lngEnc);
        if (!isValidCoordinate(lat, lng)) return null;
        double[] display = toDisplayCoord(lat, lng, sourceCoordType, displayCoordType);
        return GeoPointVO.builder().lat(display[0]).lng(display[1]).coordType(displayCoordType).accuracyM(accuracyM != null ? accuracyM.doubleValue() : null).ts(ts).build();
    }

    private CoordTypeVO resolveDisplayCoordType(List<TrackPoint> trackPoints) {
        // 前端地图统一按 GCJ02 显示；数据库内部可存 WGS84/GCJ02，但返回前端必须统一转成 GCJ02。
        return CoordTypeVO.GCJ02;
    }

    private CoordType resolveSourceCoordType(List<TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return CoordType.WGS84;
        }
        for (TrackPoint point : trackPoints) {
            if (point.getRawCoordType() != null) {
                return point.getRawCoordType();
            }
        }
        return CoordType.WGS84;
    }

    private CoordType parseCoordTypeOrDefault(Object input, CoordType defaultValue) {
        if (input == null) {
            return defaultValue;
        }
        try {
            return CoordType.valueOf(String.valueOf(input).trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double[] toDisplayCoord(double lat, double lng, CoordType sourceCoordType, CoordTypeVO displayCoordType) {
        if (displayCoordType == CoordTypeVO.GCJ02) {
            if (sourceCoordType == CoordType.GCJ02) {
                return new double[]{lat, lng};
            }
            return GeoUtils.wgs84ToGcj02(lat, lng);
        }
        if (sourceCoordType == CoordType.GCJ02) {
            return GeoUtils.gcj02ToWgs84(lat, lng);
        }
        return new double[]{lat, lng};
    }

    private String formatDistance(Long meters) { if (meters == null || meters <= 0) return "0 m"; if (meters >= 1000) return String.format(Locale.ROOT, "%.1f km", meters / 1000.0); return meters + " m"; }
    private Long normalizePlaceDuration(Long duration) { if (duration == null) return 0L; return duration > 86_400L * 30 && duration % 1000L == 0 ? duration / 1000L : duration; }
    private double bytesToDouble(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return Double.NaN;
        long bits = 0L;
        for (int i = 0; i < 8; i++) {
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
    private boolean isValidCoordinate(double lat, double lng) { return !Double.isNaN(lat) && !Double.isNaN(lng) && lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0; }
    private String asString(Object value) { return value == null ? null : String.valueOf(value); }
    private Long defaultLong(Long value) { return value == null ? 0L : value; }
    private Integer defaultInteger(Integer value) { return value == null ? 0 : value; }
    private double defaultDouble(Double value) { return value == null ? 0.0 : value; }
    private Double toDouble(Object value) { try { return value instanceof Number n ? n.doubleValue() : (value != null ? Double.parseDouble(String.valueOf(value)) : null); } catch (Exception e) { return null; } }
    private TripMediaCounts resolveTripMediaCounts(Long tripId) {
        if (tripId == null) {
            return new TripMediaCounts(0, 0);
        }
        return new TripMediaCounts((int) photoRepository.countByTripId(tripId), (int) videoRepository.countByTripId(tripId));
    }
    private Float toFloat(Object value) { try { return value instanceof Number n ? n.floatValue() : (value != null ? Float.parseFloat(String.valueOf(value)) : null); } catch (Exception e) { return null; } }
    private Long toLong(Object value) { try { return value instanceof Number n ? n.longValue() : (value != null ? Long.parseLong(String.valueOf(value)) : null); } catch (Exception e) { return null; } }
    private TripStatusVO toTripStatusVO(TripStatus status) { return status == null ? null : TripStatusVO.valueOf(status.name()); }
    private PrivacyModeVO toPrivacyModeVO(PrivacyMode privacyMode) { return privacyMode == null ? null : PrivacyModeVO.valueOf(privacyMode.name()); }
    private StoryBlockTypeVO toStoryBlockTypeVO(BlockType type) { if (type == null) return null; try { return StoryBlockTypeVO.valueOf(type.name()); } catch (Exception e) { return StoryBlockTypeVO.TEXT; } }
    private PrivacyMode parsePrivacyModeOrDefault(String input, PrivacyMode defaultValue) { try { return input == null || input.trim().isEmpty() ? defaultValue : PrivacyMode.valueOf(input.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return defaultValue; } }
    private String stringifyId(Long value) { return value == null ? null : String.valueOf(value); }

    @Override
    public List<Map<String, Object>> getTripMedia(Long userId, Long tripId, String type) {
        getUserTripOrThrow(userId, tripId);
        List<Map<String, Object>> result = new ArrayList<>();

        if (type == null || "photo".equalsIgnoreCase(type)) {
            List<Photo> photos = photoRepository.findByTripId(tripId);
            for (Photo photo : photos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", stringifyId(photo.getId()));
                item.put("type", "photo");
                item.put("url", photo.getObjectKey());
                item.put("thumbnailUrl", photo.getObjectKey());
                item.put("capturedAt", photo.getCaptureTsOverride() != null ? photo.getCaptureTsOverride() : (photo.getShotTimeExif() != null ? photo.getShotTimeExif().getTime() : null));
                item.put("createdAt", photo.getCreatedAt() != null ? photo.getCreatedAt().getTime() : null);
                item.put("caption", photo.getUserCaption());
                item.put("locationName", photo.getLocationName());
                item.put("privacyMode", photo.getPrivacyMode() != null ? photo.getPrivacyMode().name() : null);
                item.put("isCover", Boolean.TRUE.equals(photo.getIsCover()));
                item.put("noteId", stringifyId(photo.getNoteId()));
                item.put("bindingStatus", photo.getBindingStatus());
                item.put("captureCoordType", photo.getCaptureCoordType());
                item.put("captureCoordSource", photo.getCaptureCoordSource());
                byte[] latBytes = resolvePhotoLocationLatBytes(photo);
                byte[] lngBytes = resolvePhotoLocationLngBytes(photo);
                Double lat = bytesToDouble(latBytes);
                Double lng = bytesToDouble(lngBytes);
                if (isValidCoordinate(lat, lng)) {
                    double[] display = toDisplayCoord(lat, lng, resolvePhotoCoordType(photo), CoordTypeVO.GCJ02);
                    Map<String, Object> location = new LinkedHashMap<>();
                    location.put("lat", display[0]);
                    location.put("lng", display[1]);
                    location.put("name", photo.getLocationName());
                    location.put("coordType", "GCJ02");
                    item.put("location", location);
                }
                result.add(item);
            }
        }

        if (type == null || "video".equalsIgnoreCase(type)) {
            List<Video> videos = videoRepository.findByTripId(tripId);
            for (Video video : videos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", stringifyId(video.getId()));
                item.put("type", "video");
                item.put("url", video.getObjectKey());
                item.put("thumbnailUrl", video.getThumbnailObjectKey());
                item.put("duration", video.getDurationSec());
                item.put("capturedAt", video.getCaptureTsOverride() != null ? video.getCaptureTsOverride() : (video.getShotTimeExif() != null ? video.getShotTimeExif().getTime() : null));
                item.put("createdAt", video.getCreatedAt() != null ? video.getCreatedAt().getTime() : null);
                item.put("caption", video.getUserCaption());
                item.put("locationName", video.getLocationName());
                item.put("privacyMode", video.getPrivacyMode() != null ? video.getPrivacyMode().name() : null);
                item.put("noteId", stringifyId(video.getNoteId()));
                item.put("processingStatus", video.getProcessingStatus() != null ? video.getProcessingStatus().name() : null);
                item.put("bindingStatus", video.getBindingStatus());
                item.put("captureCoordType", video.getCaptureCoordType());
                item.put("captureCoordSource", video.getCaptureCoordSource());
                item.put("resolution", video.getResolution());
                byte[] latBytes = resolveVideoLocationLatBytes(video);
                byte[] lngBytes = resolveVideoLocationLngBytes(video);
                Double lat = bytesToDouble(latBytes);
                Double lng = bytesToDouble(lngBytes);
                if (isValidCoordinate(lat, lng)) {
                    double[] display = toDisplayCoord(lat, lng, resolveVideoCoordType(video), CoordTypeVO.GCJ02);
                    Map<String, Object> location = new LinkedHashMap<>();
                    location.put("lat", display[0]);
                    location.put("lng", display[1]);
                    location.put("name", video.getLocationName());
                    location.put("coordType", "GCJ02");
                    item.put("location", location);
                }
                result.add(item);
            }
        }

        result.sort((a, b) -> {
            Long timeA = (Long) a.get("capturedAt") != null ? (Long) a.get("capturedAt") : (Long) a.get("createdAt");
            Long timeB = (Long) b.get("capturedAt") != null ? (Long) b.get("capturedAt") : (Long) b.get("createdAt");
            if (timeA == null) timeA = 0L;
            if (timeB == null) timeB = 0L;
            return timeB.compareTo(timeA);
        });

        return result;
    }

    private static final class RouteMapPayload {
        private TrackPolylineVO matchedPolyline;
        private TrackPolylineVO reconstructedPolyline;
        private List<TrackPolylineVO> matchedSegments;
        private List<TrackPolylineVO> reconstructedSegments;
        private Map<String, Object> matchingDiagnostics;
        private String routeSource;
        private String routeSyncStatus;
        private String routeGeneratedAt;
    }

    private record TripMediaCounts(int photoCount, int videoCount) {}
    private record StoryTimelineItem(Date sortTime, int order, StoryBlockVO block) {}
}

