package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.*;
import com.travel.travel_system.model.dto.MapMatchingResult;
import com.travel.travel_system.model.enums.BlockType;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.TrackPointSource;
import com.travel.travel_system.model.enums.TripStatus;
import com.travel.travel_system.repository.*;
import com.travel.travel_system.service.AiService;
import com.travel.travel_system.service.TrackPointService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.vo.*;
import com.travel.travel_system.vo.enums.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TripServiceImpl implements TripService {

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
    private TrackPointService trackPointService;

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
        return tripRepository.save(trip);
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
        return tripRepository.findAll((root, query, cb) -> {
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
        return tripRepository.save(trip);
    }

    @Override
    @Transactional
    public void updateTripPrivacy(Long tripId, String privacyMode) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        trip.setPrivacyMode(parsePrivacyModeOrDefault(privacyMode, trip.getPrivacyMode()));
        trip.setUpdatedAt(new Date());
        tripRepository.save(trip);
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
        trip.setEndTime(new Date());
        trip.setStatus(TripStatus.PROCESSING);
        trip.setUpdatedAt(new Date());
        Trip saved = tripRepository.save(trip);
        try {
            settleTrip(tripId);
        } catch (Exception ignored) {
        }
        return saved;
    }

    @Override
    @Transactional
    public Trip pauseTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (trip.getStatus() != TripStatus.ACTIVE) {
            throw new RuntimeException("TRIP_409 只有进行中的行程才可暂停");
        }
        trip.setStatus(TripStatus.PAUSED);
        trip.setUpdatedAt(new Date());
        return tripRepository.save(trip);
    }

    @Override
    @Transactional
    public Trip resumeTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));
        if (trip.getStatus() != TripStatus.PAUSED) {
            throw new RuntimeException("TRIP_409 只有暂停状态的行程才可恢复");
        }
        trip.setStatus(TripStatus.ACTIVE);
        trip.setUpdatedAt(new Date());
        return tripRepository.save(trip);
    }

    @Override
    @Transactional
    public Trip settleTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        if (!trackPoints.isEmpty()) {
            trip.setDistanceM(calculateTotalDistance(trackPoints));
            if (trackPoints.size() >= 2) {
                long duration = (trackPoints.get(trackPoints.size() - 1).getTs() - trackPoints.get(0).getTs()) / 1000L;
                trip.setDurationSec(Math.max(duration, 0L));
            }
            calculateAndSaveBBox(tripId, trackPoints);
            try {
                performMapMatching(tripId);
            } catch (Exception ignored) {
            }
        }

        trip.setPhotoCount((int) photoRepository.countByTripId(tripId));
        trip.setVideoCount((int) videoRepository.countByTripId(tripId));

        generatePlaceSummaries(tripId);

        try {
            aiService.generateTripSummary(tripId);
        } catch (Exception ignored) {
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
        statistics.put("durationText", formatDuration(trip.getDurationSec()));
        statistics.put("photoCount", defaultInteger(trip.getPhotoCount()));
        statistics.put("videoCount", defaultInteger(trip.getVideoCount()));
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
        Optional<TripAiSummary> aiSummary = tripAiSummaryRepository.findByTripId(tripId);
        if (aiSummary.isPresent()) {
            TripAiSummary summary = aiSummary.get();
            story.put("overview", summary.getOverview());
            story.put("highlights", parseHighlights(summary.getHighlights()));
            story.put("routeSummary", summary.getRouteSummary());
            story.put("bestMoment", summary.getBestMoment());
        } else {
            story.put("overview", buildDefaultStory(trip));
            story.put("highlights", buildDefaultHighlights(trip));
            story.put("routeSummary", "行程路线信息");
            story.put("bestMoment", "旅途中的美好瞬间");
        }
        return story;
    }

    @Override
    public TripDetailVO getTripDetail(Long userId, Long tripId) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        ensureGeneratedArtifacts(tripId);
        return TripDetailVO.builder()
                .trip(buildTripSummary(trip, (int) placeSummaryRepository.countByTripId(tripId)))
                .map(getTripMap(userId, tripId))
                .places(getTripPlaces(userId, tripId))
                .storyBlocks((List<StoryBlockVO>) getTripStoryBlocks(userId, tripId, 1, 500).get("items"))
                .aiSummary(getTripAiSummary(userId, tripId, false))
                .build();
    }

    @Override
    public TripMapVO getTripMap(Long userId, Long tripId) {
        getUserTripOrThrow(userId, tripId);
        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        TripMapVO.BBoxVO bbox = buildBBox(tripId, trackPoints);
        GeoPointVO center = buildCenter(bbox);

        TrackPolylineVO rawPolyline = emptyPolyline();
        TrackPolylineVO matchedPolyline = emptyPolyline();
        TrackPolylineVO reconstructedPolyline = emptyPolyline();
        if (!trackPoints.isEmpty()) {
            Map<String, TrackPolylineVO> processed = trackPointService.processTrackPoints(tripId, toOriginalPointMaps(trackPoints));
            rawPolyline = processed.getOrDefault("rawPolyline", emptyPolyline());
            matchedPolyline = processed.getOrDefault("matchedPolyline", emptyPolyline());
            reconstructedPolyline = processed.getOrDefault("reconstructedPolyline", emptyPolyline());
        }
        return TripMapVO.builder()
                .center(center)
                .zoom(resolveZoom(bbox))
                .bbox(bbox)
                .rawPolyline(rawPolyline)
                .matchedPolyline(matchedPolyline)
                .reconstructedPolyline(reconstructedPolyline)
                .markers(buildMapMarkers(tripId))
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
        getUserTripOrThrow(userId, tripId);
        Pageable pageable = PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1), Sort.by("sortTime").ascending().and(Sort.by("sortIndex").ascending()));
        Page<StoryBlock> page = storyBlockRepository.findByTripId(tripId, pageable);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", page.getContent().stream().map(this::toStoryBlockVO).collect(Collectors.toList()));
        data.put("total", page.getTotalElements());
        data.put("pageNo", pageNo);
        data.put("pageSize", pageSize);
        return data;
    }

    @Override
    public TripAISummaryVO getTripAiSummary(Long userId, Long tripId, boolean regenerate) {
        getUserTripOrThrow(userId, tripId);
        if (regenerate) {
            return toTripAiSummaryVO(tripId, aiService.generateTripSummary(tripId));
        }
        Optional<TripAiSummary> existing = tripAiSummaryRepository.findByTripId(tripId);
        if (existing.isPresent()) {
            TripAiSummary summary = existing.get();
            return TripAISummaryVO.builder()
                    .tripId(tripId)
                    .overview(summary.getOverview())
                    .highlights(parseHighlights(summary.getHighlights()))
                    .routeSummary(summary.getRouteSummary())
                    .bestMoment(summary.getBestMoment())
                    .generatedAt(formatDateTime(summary.getGeneratedAt()))
                    .version(summary.getVersion())
                    .build();
        }
        return toTripAiSummaryVO(tripId, aiService.generateTripSummary(tripId));
    }

    @Override
    public List<StoryBlockVO> rebuildTripStoryBlocks(Long userId, Long tripId) {
        getUserTripOrThrow(userId, tripId);
        return aiService.rebuildStoryBlocks(tripId).stream().map(this::toStoryBlockVO).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getActiveTrip(Long userId) {
        List<Trip> activeTrips = tripRepository.findByUserIdAndStatus(userId, TripStatus.ACTIVE);
        if (activeTrips == null || activeTrips.isEmpty()) {
            return null;
        }
        activeTrips.sort(Comparator.comparing(Trip::getStartTime, Comparator.nullsLast(Date::compareTo)).reversed());
        Trip trip = activeTrips.get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", trip.getId());
        data.put("title", trip.getTitle());
        data.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
        data.put("startTime", formatDateTime(trip.getStartTime()));
        return data;
    }

    @Override
    @Transactional
    public Integer uploadTrackPoints(Long userId, Long tripId, List<Map<String, Object>> points) {
        Trip trip = getUserTripOrThrow(userId, tripId);
        if (points == null || points.isEmpty()) {
            return 0;
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
            point.setRawCoordType(CoordType.WGS84);
            trackPoints.add(point);
        }
        if (!trackPoints.isEmpty()) {
            trackPointService.cacheTrackPoints(tripId, trackPoints);
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
        data.put("lastUpdatedAt", formatDateTime(trip.getUpdatedAt()));
        return data;
    }

    private void ensureGeneratedArtifacts(Long tripId) {
        if (storyBlockRepository.countByTripId(tripId) == 0) {
            try { aiService.rebuildStoryBlocks(tripId); } catch (Exception ignored) {}
        }
        if (tripAiSummaryRepository.findByTripId(tripId).isEmpty()) {
            try { aiService.generateTripSummary(tripId); } catch (Exception ignored) {}
        }
    }

    private List<MapMarkerVO> buildMapMarkers(Long tripId) {
        List<MapMarkerVO> markers = new ArrayList<>();
        for (PlaceSummary place : placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId)) {
            GeoPointVO point = buildGeoPoint(place.getCenterLatEnc(), place.getCenterLngEnc(), null, null);
            if (point == null) continue;
            markers.add(MapMarkerVO.builder()
                    .id("place-" + place.getId())
                    .type(null)
                    .point(point)
                    .title(place.getPoiName())
                    .subTitle(formatDuration(normalizePlaceDuration(place.getDurationSec())))
                    .placeId(place.getId())
                    .calloutText(place.getPoiName())
                    .build());
        }
        return markers;
    }

    private TripDetailVO.TripSummaryVO buildTripSummary(Trip trip, int placeCount) {
        return TripDetailVO.TripSummaryVO.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .status(toTripStatusVO(trip.getStatus()))
                .privacyMode(toPrivacyModeVO(trip.getPrivacyMode()))
                .summaryText(trip.getSummaryText())
                .cover(null)
                .startTime(formatDateTime(trip.getStartTime()))
                .endTime(formatDateTime(trip.getEndTime()))
                .distanceM(defaultLong(trip.getDistanceM()))
                .distanceText(formatDistance(trip.getDistanceM()))
                .durationSec(defaultLong(trip.getDurationSec()))
                .durationText(formatDuration(trip.getDurationSec()))
                .photoCount(defaultInteger(trip.getPhotoCount()))
                .videoCount(defaultInteger(trip.getVideoCount()))
                .placeCount(placeCount)
                .build();
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
                .centerPoint(buildGeoPoint(place.getCenterLatEnc(), place.getCenterLngEnc(), null, null))
                .startTime(formatDateTime(place.getStartTime()))
                .endTime(formatDateTime(place.getEndTime()))
                .durationSec(normalizePlaceDuration(place.getDurationSec()))
                .durationText(formatDuration(normalizePlaceDuration(place.getDurationSec())))
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
        return StoryBlockVO.builder()
                .id(block.getId() != null ? String.valueOf(block.getId()) : null)
                .tripId(block.getTripId())
                .type(toStoryBlockTypeVO(block.getBlockType()))
                .sortTime(formatDateTime(block.getSortTime()))
                .displayTimeText(formatDisplayTime(block.getSortTime()))
                .locationName(relatedPlace != null ? relatedPlace.getPoiName() : null)
                .point(relatedPlace != null ? relatedPlace.getCenterPoint() : null)
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
                .createdAt(formatDateTime(block.getSortTime()))
                .type(inferMediaType(block.getBlockType()))
                .locationName(block.getTitle())
                .build();
    }

    private MediaAssetVO toPhotoMediaVO(Photo photo) {
        return MediaAssetVO.builder()
                .id(photo.getId())
                .tripId(photo.getTripId())
                .type(MediaTypeVO.PHOTO)
                .url(photo.getObjectKey())
                .thumbnailUrl(photo.getObjectKey())
                .shotTime(formatDateTime(photo.getShotTimeExif()))
                .createdAt(formatDateTime(photo.getCreatedAt()))
                .caption(photo.getUserCaption())
                .privacyMode(toPrivacyModeVO(photo.getPrivacyMode()))
                .isCover(Boolean.TRUE.equals(photo.getIsCover()))
                .point(buildGeoPoint(photo.getLatEnc(), photo.getLngEnc(), null, null))
                .build();
    }

    private MediaAssetVO toVideoMediaVO(Video video) {
        return MediaAssetVO.builder()
                .id(video.getId())
                .tripId(video.getTripId())
                .type(MediaTypeVO.VIDEO)
                .url(video.getObjectKey())
                .thumbnailUrl(video.getThumbnailObjectKey())
                .shotTime(formatDateTime(video.getShotTimeExif()))
                .createdAt(formatDateTime(video.getCreatedAt()))
                .durationSec(video.getDurationSec())
                .resolution(video.getResolution())
                .caption(video.getUserCaption())
                .privacyMode(toPrivacyModeVO(video.getPrivacyMode()))
                .point(buildGeoPoint(video.getLatEnc(), video.getLngEnc(), null, null))
                .build();
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
            highlights = list.stream().map(String::valueOf).collect(Collectors.toList());
        }
        return TripAISummaryVO.builder()
                .tripId(tripId)
                .overview(asString(summary.get("overview")))
                .highlights(highlights)
                .routeSummary(asString(summary.get("routeSummary")))
                .bestMoment(asString(summary.get("bestMoment")))
                .generatedAt(formatGeneratedAt(summary.get("generatedAt")))
                .version(asString(summary.get("version")))
                .build();
    }

    private List<String> parseHighlights(String highlightsText) {
        if (highlightsText == null || highlightsText.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(highlightsText.split("\\r?\\n")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private long calculateTotalDistance(List<TrackPoint> trackPoints) {
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

    private void calculateAndSaveBBox(Long tripId, List<TrackPoint> trackPoints) {
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
        if (trackPoints.size() < 10) return;
        placeSummaryRepository.deleteByTripId(tripId);
        int chunkSize = 10;
        for (int i = 0; i < trackPoints.size(); i += chunkSize) {
            List<TrackPoint> chunk = trackPoints.subList(i, Math.min(i + chunkSize, trackPoints.size()));
            PlaceSummary placeSummary = new PlaceSummary();
            placeSummary.setUserId(trackPoints.get(0).getUserId());
            placeSummary.setTripId(tripId);
            double avgLat = 0.0, avgLng = 0.0;
            for (TrackPoint point : chunk) {
                avgLat += bytesToDouble(point.getLatEnc());
                avgLng += bytesToDouble(point.getLngEnc());
            }
            avgLat /= chunk.size(); avgLng /= chunk.size();
            placeSummary.setCenterLatEnc(doubleToBytes(avgLat));
            placeSummary.setCenterLngEnc(doubleToBytes(avgLng));
            placeSummary.setStartTime(new Date(chunk.get(0).getTs()));
            placeSummary.setEndTime(new Date(chunk.get(chunk.size() - 1).getTs()));
            placeSummary.setDurationSec((placeSummary.getEndTime().getTime() - placeSummary.getStartTime().getTime()) / 1000L);
            placeSummary.setPoiName("地点 " + (i / chunkSize + 1));
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
        StringBuilder story = new StringBuilder();
        story.append("这是一次").append(trip.getTitle()).append("的旅程");
        if (defaultLong(trip.getDistanceM()) > 0) story.append("，总行程").append(formatDistance(trip.getDistanceM()));
        if (defaultLong(trip.getDurationSec()) > 0) story.append("，耗时").append(formatDuration(trip.getDurationSec()));
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

    private void performMapMatching(Long tripId) {
        try {
            List<MapMatchingResult> matchedResults = trackPointService.matchTrajectory(tripId);
        } catch (Exception ignored) {
        }
    }

    private TripMapVO.BBoxVO buildBBox(Long tripId, List<TrackPoint> trackPoints) {
        Optional<TripBBox> bboxOpt = tripBBoxRepository.findByTripId(tripId);
        if (bboxOpt.isPresent()) {
            TripBBox bbox = bboxOpt.get();
            return TripMapVO.BBoxVO.builder().minLat((double) bbox.getMinLat()).minLng((double) bbox.getMinLng()).maxLat((double) bbox.getMaxLat()).maxLng((double) bbox.getMaxLng()).build();
        }
        if (trackPoints == null || trackPoints.isEmpty()) return TripMapVO.BBoxVO.builder().minLat(0.0).minLng(0.0).maxLat(0.0).maxLng(0.0).build();
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
        for (TrackPoint point : trackPoints) {
            double lat = bytesToDouble(point.getLatEnc()), lng = bytesToDouble(point.getLngEnc());
            if (!isValidCoordinate(lat, lng)) continue;
            minLat = Math.min(minLat, lat); maxLat = Math.max(maxLat, lat); minLng = Math.min(minLng, lng); maxLng = Math.max(maxLng, lng);
        }
        if (minLat == Double.MAX_VALUE) minLat = maxLat = minLng = maxLng = 0.0;
        return TripMapVO.BBoxVO.builder().minLat(minLat).minLng(minLng).maxLat(maxLat).maxLng(maxLng).build();
    }

    private GeoPointVO buildCenter(TripMapVO.BBoxVO bbox) {
        return GeoPointVO.builder().lat((defaultDouble(bbox.getMinLat()) + defaultDouble(bbox.getMaxLat())) / 2.0).lng((defaultDouble(bbox.getMinLng()) + defaultDouble(bbox.getMaxLng())) / 2.0).coordType(CoordTypeVO.WGS84).build();
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

    private TrackPolylineVO emptyPolyline() {
        return TrackPolylineVO.builder().points(Collections.emptyList()).distanceM(0L).simplified(Boolean.FALSE).build();
    }

    private GeoPointVO buildGeoPoint(byte[] latEnc, byte[] lngEnc, Float accuracyM, Long ts) {
        double lat = bytesToDouble(latEnc), lng = bytesToDouble(lngEnc);
        if (!isValidCoordinate(lat, lng)) return null;
        return GeoPointVO.builder().lat(lat).lng(lng).coordType(CoordTypeVO.WGS84).accuracyM(accuracyM != null ? accuracyM.doubleValue() : null).ts(ts).build();
    }

    private String formatDateTime(Date date) { return date == null ? null : DATE_FORMAT.format(date); }
    private String formatGeneratedAt(Object value) { return value instanceof Date d ? formatDateTime(d) : (value != null ? String.valueOf(value) : null); }
    private String formatDisplayTime(Date date) { if (date == null) return null; SimpleDateFormat sdf = new SimpleDateFormat("HH:mm"); sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return sdf.format(date); }
    private String formatDistance(Long meters) { if (meters == null || meters <= 0) return "0 m"; if (meters >= 1000) return String.format(Locale.ROOT, "%.1f km", meters / 1000.0); return meters + " m"; }
    private String formatDuration(Long seconds) { if (seconds == null || seconds <= 0) return "0 分钟"; long hours = seconds / 3600L, minutes = (seconds % 3600L) / 60L; return hours > 0 ? hours + " 小时 " + minutes + " 分钟" : Math.max(1, minutes) + " 分钟"; }
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
    private Float toFloat(Object value) { try { return value instanceof Number n ? n.floatValue() : (value != null ? Float.parseFloat(String.valueOf(value)) : null); } catch (Exception e) { return null; } }
    private Long toLong(Object value) { try { return value instanceof Number n ? n.longValue() : (value != null ? Long.parseLong(String.valueOf(value)) : null); } catch (Exception e) { return null; } }
    private TripStatusVO toTripStatusVO(TripStatus status) { return status == null ? null : TripStatusVO.valueOf(status.name()); }
    private PrivacyModeVO toPrivacyModeVO(PrivacyMode privacyMode) { return privacyMode == null ? null : PrivacyModeVO.valueOf(privacyMode.name()); }
    private StoryBlockTypeVO toStoryBlockTypeVO(BlockType type) { if (type == null) return null; try { return StoryBlockTypeVO.valueOf(type.name()); } catch (Exception e) { return StoryBlockTypeVO.TEXT; } }
    private PrivacyMode parsePrivacyModeOrDefault(String input, PrivacyMode defaultValue) { try { return input == null || input.trim().isEmpty() ? defaultValue : PrivacyMode.valueOf(input.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return defaultValue; } }
}
