package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.TripSegment;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.MatchMethod;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.TripSegmentRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.MediaAnchorProjectionService;
import com.travel.travel_system.utils.GeoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Service
public class MediaAnchorProjectionServiceImpl implements MediaAnchorProjectionService {

    /**
     * 距离阈值：
     * - 80m 内可认为明显贴近行程
     * - 300m 内可认为弱贴近
     * - 40m 内的投影点可参与路线辅助
     */
    private static final double STRONG_NEAR_METERS = 80.0;
    private static final double WEAK_NEAR_METERS = 300.0;

    /**
     * 时间容忍：
     * 媒体时间没有严格落在 segment 内时，允许向最近 segment 容忍 10 分钟
     */
    private static final long NEAREST_SEGMENT_TOLERANCE_MS = 2 * 60 * 1000L; // 2分钟，不再是10分钟

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private AnchorRepository anchorRepository;

    @Autowired
    private TripSegmentRepository tripSegmentRepository;

    /**
     * 这里直接注入实现类，避免再新加 facade / DTO
     */
    @Autowired
    private TrackPointServiceImpl trackPointService;

    @Override
    @Transactional
    public Anchor projectPhotoAnchor(Long photoId, Long tripId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("照片不存在，photoId=" + photoId));
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId=" + tripId));

        MediaFacts facts = MediaFacts.fromPhoto(photo);
        ProjectionDecision decision = decideProjection(trip, facts);

        boolean oldRouteEligible = false;
        Anchor existing = anchorRepository.findByPhotoId(photoId).stream().findFirst().orElse(null);
        if (existing != null) {
            oldRouteEligible = Boolean.TRUE.equals(existing.getRouteEligible());
        }

        photo.setBindingStatus(decision.bindingStatus);
        photo.setBindingScore(decision.bindingScore);
        photoRepository.save(photo);

        Anchor saved = upsertPhotoAnchor(existing, photo, decision);

        // 若之前或现在是 routeEligible，都触发一次 dirty，避免路线辅助点增删后缓存不一致
        if (oldRouteEligible || Boolean.TRUE.equals(saved.getRouteEligible())) {
            trackPointService.markTripMatchDirty(tripId);
        }
        return saved;
    }

    @Override
    @Transactional
    public Anchor projectVideoAnchor(Long videoId, Long tripId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId=" + videoId));
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId=" + tripId));

        MediaFacts facts = MediaFacts.fromVideo(video);
        ProjectionDecision decision = decideProjection(trip, facts);

        boolean oldRouteEligible = false;
        Anchor existing = anchorRepository.findByVideoId(videoId).stream().findFirst().orElse(null);
        if (existing != null) {
            oldRouteEligible = Boolean.TRUE.equals(existing.getRouteEligible());
        }

        video.setBindingStatus(decision.bindingStatus);
        video.setBindingScore(decision.bindingScore);
        videoRepository.save(video);

        Anchor saved = upsertVideoAnchor(existing, video, decision);

        if (oldRouteEligible || Boolean.TRUE.equals(saved.getRouteEligible())) {
            trackPointService.markTripMatchDirty(tripId);
        }
        return saved;
    }

    @Override
    @Transactional
    public void refreshTripMediaAnchors(Long tripId) {
        for (Photo photo : photoRepository.findByTripIdOrderByShotTimeExifAsc(tripId)) {
            projectPhotoAnchor(photo.getId(), tripId);
        }
        for (Video video : videoRepository.findByTripIdOrderByShotTimeExifAsc(tripId)) {
            projectVideoAnchor(video.getId(), tripId);
        }
    }

    @Override
    public void markTripRouteDirtyIfNeeded(Long tripId) {

    }

    private ProjectionDecision decideProjection(Trip trip, MediaFacts facts) {
        ProjectionDecision decision = new ProjectionDecision();
        decision.tripId = trip.getId();
        decision.userId = trip.getUserId();
        decision.mediaTs = facts.effectiveTs;
        decision.matchMethod = defaultMatchMethod(facts);
        decision.bindingStatus = "PENDING";
        decision.bindingScore = 0.0f;
        decision.routeEligible = false;
        decision.projectionStatus = "PENDING";
        decision.confidence = 0.20f;
        decision.lat = null;
        decision.lng = null;
        decision.matchedTs = null;
        decision.segmentId = null;

        // 既没有时间也没有坐标：只能 pending
        if (facts.effectiveTs == null && !facts.hasCoord()) {
            return decision;
        }

        TripSegment segment = findBestSegment(trip.getId(), facts.effectiveTs);
        if (segment != null) {
            decision.segmentId = segment.getId();
        }

        float bindingScore = scoreInTrip(trip, facts, segment);
        decision.bindingScore = bindingScore;

        if (bindingScore >= 0.70f) {
            decision.bindingStatus = "IN_TRIP";
        } else if (bindingScore < 0.35f) {
            decision.bindingStatus = "OUT_OF_TRIP";
            decision.projectionStatus = "OUT_OF_TRIP";

            // OUT_OF_TRIP 时可以保留原始坐标用于后续人工确认
            if (facts.hasCoord()) {
                double wgs84Lat = facts.lat;
                double wgs84Lng = facts.lng;
                if (facts.coordType == CoordType.GCJ02) {
                    double[] converted = GeoUtils.gcj02ToWgs84(facts.lat, facts.lng);
                    wgs84Lat = converted[0];
                    wgs84Lng = converted[1];
                }
                decision.lat = wgs84Lat;
                decision.lng = wgs84Lng;
                decision.confidence = 0.30f;
            }
            return decision;
        } else {
            decision.bindingStatus = "PENDING";
        }

        // 1) 有时间 + 有坐标 + 命中 segment：优先走观测点投影
        if (facts.effectiveTs != null && facts.hasCoord() && segment != null) {
            TrackPointServiceImpl.RouteSupportProjection projection =
                    trackPointService.projectObservationToRoute(
                            trip.getId(),
                            segment.getId(),
                            facts.effectiveTs,
                            facts.lat,
                            facts.lng,
                            facts.coordType == null ? CoordType.GCJ02 : facts.coordType
                    );

            if (projection != null && projection.getLat() != null && projection.getLng() != null) {
                decision.lat = projection.getLat();
                decision.lng = projection.getLng();
                decision.matchedTs = projection.getMatchedTs();
                decision.routeEligible = projection.isRouteEligible();
                decision.projectionStatus = projection.isRouteEligible() ? "PROJECTED" : "PENDING";
                decision.matchMethod = facts.manualSource ? MatchMethod.MANUAL_PICK : MatchMethod.EXIF_DIRECT;
                decision.confidence = projection.getConfidence() == null
                        ? (projection.isRouteEligible() ? 0.85f : 0.60f)
                        : projection.getConfidence();
                return decision;
            }
        }

        // 2) 有时间但没坐标，或坐标投影失败：按时间投影到轨迹
        if (facts.effectiveTs != null && segment != null) {
            TrackPointServiceImpl.RouteSupportProjection projection =
                    trackPointService.projectTimestampToRoute(trip.getId(), segment.getId(), facts.effectiveTs);

            if (projection != null && projection.getLat() != null && projection.getLng() != null) {
                decision.lat = projection.getLat();
                decision.lng = projection.getLng();
                decision.matchedTs = projection.getMatchedTs();
                decision.routeEligible = false; // 时间插值点不反向拉动路径，只做弱辅助/marker
                decision.projectionStatus = "PROJECTED";
                decision.matchMethod = MatchMethod.INTERPOLATE;
                decision.confidence = projection.getConfidence() == null ? 0.65f : projection.getConfidence();
                return decision;
            }
        }

        // 3) 只有坐标，没有可用时间：保留原始坐标，等待后续人工确认或补时间
        if (facts.hasCoord()) {
            double wgs84Lat = facts.lat;
            double wgs84Lng = facts.lng;
            if (facts.coordType == CoordType.GCJ02) {
                double[] converted = GeoUtils.gcj02ToWgs84(facts.lat, facts.lng);
                wgs84Lat = converted[0];
                wgs84Lng = converted[1];
            }
            decision.lat = wgs84Lat;
            decision.lng = wgs84Lng;
            decision.routeEligible = false;
            decision.projectionStatus = "PENDING";
            decision.confidence = facts.manualSource ? 0.75f : 0.50f;
            decision.matchMethod = facts.manualSource ? MatchMethod.MANUAL_PICK : MatchMethod.EXIF_DIRECT;
        }

        return decision;
    }

    private float scoreInTrip(Trip trip, MediaFacts facts, TripSegment segment) {
        float score = 0.0f;

        // 时间命中 segment
        if (segment != null) {
            score += 0.55f;
        } else if (facts.effectiveTs != null && isWithinTripWindow(trip, facts.effectiveTs)) {
            score += 0.20f;
        }

        // 空间靠近轨迹
        if (facts.hasCoord()) {
            double distance = trackPointService.minDistanceToTrip(trip.getId(), facts.lat, facts.lng, facts.coordType);
            if (distance <= STRONG_NEAR_METERS) {
                score += 0.35f;
            } else if (distance <= WEAK_NEAR_METERS) {
                score += 0.15f;
            } else {
                score -= 0.15f;
            }
        }

        if (score < 0.0f) return 0.0f;
        if (score > 1.0f) return 1.0f;
        return score;
    }

    private boolean isWithinTripWindow(Trip trip, Long ts) {
        if (trip == null || ts == null || trip.getStartTime() == null) {
            return false;
        }
        long start = trip.getStartTime().getTime();
        long end = trip.getEndTime() == null ? System.currentTimeMillis() : trip.getEndTime().getTime();
        return ts >= start && ts <= end;
    }

    /**
     * 优先找包含 ts 的 segment；
     * 找不到时，允许用“最近 segment 且相差 <= 10 分钟”兜底。
     */
    private TripSegment findBestSegment(Long tripId, Long ts) {
        if (tripId == null || ts == null) {
            return null;
        }

        List<TripSegment> segments = tripSegmentRepository.findByTripIdOrderBySegmentNoAsc(tripId);
        if (segments == null || segments.isEmpty()) {
            return null;
        }

        // 1. 先找严格命中的 segment
        for (TripSegment segment : segments) {
            long start = segment.getStartTs() == null ? Long.MIN_VALUE : segment.getStartTs();
            long end = segment.getEndTs() == null ? Long.MAX_VALUE : segment.getEndTs();
            if (ts >= start && ts <= end) {
                return segment;
            }
        }

        // 2. 如果 ts 落在两个 segment 之间的 gap（暂停区间），直接返回 null，不做最近兜底
        for (int i = 0; i < segments.size() - 1; i++) {
            TripSegment current = segments.get(i);
            TripSegment next = segments.get(i + 1);

            Long gapStart = current.getEndTs();
            Long gapEnd = next.getStartTs();
            if (gapStart != null && gapEnd != null && ts > gapStart && ts < gapEnd) {
                return null;
            }
        }

        // 3. 只有不在 paused gap 且非常接近边界时，才允许最近兜底（2分钟）
        TripSegment nearest = null;
        long bestDelta = Long.MAX_VALUE;
        for (TripSegment segment : segments) {
            long start = segment.getStartTs() == null ? Long.MIN_VALUE : segment.getStartTs();
            long end = segment.getEndTs() == null ? Long.MAX_VALUE : segment.getEndTs();

            long delta;
            if (ts < start) {
                delta = start - ts;
            } else if (ts > end) {
                delta = ts - end;
            } else {
                delta = 0L;
            }

            if (delta < bestDelta) {
                bestDelta = delta;
                nearest = segment;
            }
        }

        if (bestDelta <= NEAREST_SEGMENT_TOLERANCE_MS) {
            return nearest;
        }
        return null;
    }

    private MatchMethod defaultMatchMethod(MediaFacts facts) {
        if (facts == null) {
            return MatchMethod.TIME_NEAREST;
        }
        if (facts.manualSource) {
            return MatchMethod.MANUAL_PICK;
        }
        if (facts.hasCoord()) {
            return MatchMethod.EXIF_DIRECT;
        }
        if (facts.effectiveTs != null) {
            return MatchMethod.INTERPOLATE;
        }
        return MatchMethod.TIME_NEAREST;
    }

    private Anchor upsertPhotoAnchor(Anchor existing, Photo photo, ProjectionDecision decision) {
        Anchor anchor = existing != null ? existing : new Anchor();
        fillCommonAnchor(anchor, decision, photo.getUserId(), photo.getTripId());
        anchor.setPhotoId(photo.getId());
        anchor.setVideoId(null);
        return anchorRepository.save(anchor);
    }

    private Anchor upsertVideoAnchor(Anchor existing, Video video, ProjectionDecision decision) {
        Anchor anchor = existing != null ? existing : new Anchor();
        fillCommonAnchor(anchor, decision, video.getUserId(), video.getTripId());
        anchor.setPhotoId(null);
        anchor.setVideoId(video.getId());
        return anchorRepository.save(anchor);
    }

    private void fillCommonAnchor(Anchor anchor, ProjectionDecision decision, Long userId, Long tripId) {
        anchor.setUserId(userId);
        anchor.setTripId(tripId);

        anchor.setMediaTs(decision.mediaTs);
        anchor.setMatchedTs(decision.matchedTs);
        anchor.setSegmentId(decision.segmentId);
        anchor.setRouteEligible(decision.routeEligible);
        anchor.setProjectionStatus(decision.projectionStatus);
        anchor.setMatchMethod(decision.matchMethod);
        anchor.setConfidence(decision.confidence);

        if (decision.mediaTs != null && decision.matchedTs != null) {
            anchor.setTimeDeltaSec((int) Math.abs((decision.mediaTs - decision.matchedTs) / 1000L));
        } else {
            anchor.setTimeDeltaSec(null);
        }

        anchor.setManualOverride(Boolean.TRUE.equals(decision.manualOverride));

        if (decision.lat != null) {
            anchor.setLatEnc(TrackPointServiceImpl.encodeDoubleStatic(decision.lat));
        } else {
            anchor.setLatEnc(null);
        }

        if (decision.lng != null) {
            anchor.setLngEnc(TrackPointServiceImpl.encodeDoubleStatic(decision.lng));
        } else {
            anchor.setLngEnc(null);
        }

        if (anchor.getCreatedAt() == null) {
            anchor.setCreatedAt(new Date());
        }
        anchor.setUpdatedAt(new Date());
    }

    private static class MediaFacts {
        Long effectiveTs;
        Double lat;
        Double lng;
        CoordType coordType;
        boolean manualSource;

        static MediaFacts fromPhoto(Photo photo) {
            MediaFacts facts = new MediaFacts();

            facts.manualSource = photo.getCaptureTsOverride() != null
                    || photo.getCaptureLatOverride() != null
                    || photo.getCaptureLngOverride() != null;

            // P0 修复：不再回退 createdAt
            facts.effectiveTs = photo.getCaptureTsOverride() != null
                    ? photo.getCaptureTsOverride()
                    : (photo.getShotTimeExif() != null ? photo.getShotTimeExif().getTime() : null);

            byte[] latBytes = photo.getCaptureLatOverride() != null ? photo.getCaptureLatOverride() : photo.getLatEnc();
            byte[] lngBytes = photo.getCaptureLngOverride() != null ? photo.getCaptureLngOverride() : photo.getLngEnc();

            if (latBytes != null && lngBytes != null) {
                facts.lat = TrackPointServiceImpl.decodeDoubleStatic(latBytes);
                facts.lng = TrackPointServiceImpl.decodeDoubleStatic(lngBytes);
            }

            if ("WGS84".equalsIgnoreCase(photo.getCaptureCoordType())) {
                facts.coordType = CoordType.WGS84;
            } else if ("GCJ02".equalsIgnoreCase(photo.getCaptureCoordType())) {
                facts.coordType = CoordType.GCJ02;
            } else if ("EXIF".equalsIgnoreCase(photo.getCaptureCoordSource())) {
                facts.coordType = CoordType.WGS84;
            } else if (facts.manualSource) {
                facts.coordType = CoordType.GCJ02;
            } else {
                facts.coordType = null;
            }

            return facts;
        }
        static MediaFacts fromVideo(Video video) {
            MediaFacts facts = new MediaFacts();

            facts.manualSource = video.getCaptureTsOverride() != null
                    || video.getCaptureLatOverride() != null
                    || video.getCaptureLngOverride() != null;

            // P0 修复：不再回退 createdAt
            facts.effectiveTs = video.getCaptureTsOverride() != null
                    ? video.getCaptureTsOverride()
                    : (video.getShotTimeExif() != null ? video.getShotTimeExif().getTime() : null);

            byte[] latBytes = video.getCaptureLatOverride() != null ? video.getCaptureLatOverride() : video.getLatEnc();
            byte[] lngBytes = video.getCaptureLngOverride() != null ? video.getCaptureLngOverride() : video.getLngEnc();

            if (latBytes != null && lngBytes != null) {
                facts.lat = TrackPointServiceImpl.decodeDoubleStatic(latBytes);
                facts.lng = TrackPointServiceImpl.decodeDoubleStatic(lngBytes);
            }

            if ("WGS84".equalsIgnoreCase(video.getCaptureCoordType())) {
                facts.coordType = CoordType.WGS84;
            } else if ("GCJ02".equalsIgnoreCase(video.getCaptureCoordType())) {
                facts.coordType = CoordType.GCJ02;
            } else if ("EXIF".equalsIgnoreCase(video.getCaptureCoordSource())) {
                facts.coordType = CoordType.WGS84;
            } else if (facts.manualSource) {
                facts.coordType = CoordType.GCJ02;
            } else {
                facts.coordType = null;
            }

            return facts;
        }

        boolean hasCoord() {
            return lat != null && lng != null;
        }
    }

    private static class ProjectionDecision {
        Long tripId;
        Long userId;

        Long mediaTs;
        Long matchedTs;
        Long segmentId;

        Double lat;
        Double lng;

        Boolean routeEligible;
        String projectionStatus;
        String bindingStatus;
        Float bindingScore;
        Float confidence;

        Boolean manualOverride;
        MatchMethod matchMethod;
    }
}
