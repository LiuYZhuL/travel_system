package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.*;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.*;
import com.travel.travel_system.service.PlaceSummaryService;
import com.travel.travel_system.service.ReverseGeocodingService;
import com.travel.travel_system.utils.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
@Service
public class PlaceSummaryServiceImpl implements PlaceSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PlaceSummaryServiceImpl.class);
    private static final String FALLBACK_CITY = "未知";
    private static final String FALLBACK_DISTRICT = "";
    private static final String FALLBACK_POI_NAME_PREFIX = "地点 ";

    private static final double CLUSTER_RADIUS_METERS = 50.0;
    private static final double CLUSTER_CENTROID_RADIUS_METERS = 65.0;
    private static final double MAX_STAY_SPREAD_METERS = 80.0;
    private static final double MAX_STAY_END_TO_END_METERS = 70.0;
    private static final double MAX_STAY_PATH_LENGTH_METERS = 250.0;
    private static final double MAX_STAY_INFERRED_SPEED_MPS = 2.8;
    private static final double MAX_STAY_REPORTED_SPEED_MPS = 3.5;
    private static final long MIN_STAY_DURATION_SEC = 60L;
    private static final long MIN_TRACK_ONLY_STAY_DURATION_SEC = 180L;
    private static final long MAX_GAP_SEC = 300L;
    private static final int MIN_POINTS_FOR_CLUSTER = 3;
    private static final int MIN_ANCHORS_FOR_STAY = 2;
    private static final Map<String, String> SEMANTIC_TAG_KEYWORDS = buildSemanticTagKeywords();

    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;

    @Autowired
    private PlaceSummaryMemberRepository placeSummaryMemberRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private AnchorRepository anchorRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ReverseGeocodingService reverseGeocodingService;

    @Override
    @Transactional
    public PlaceSummary createPlaceSummary(PlaceSummary placeSummary) {
        if (placeSummary.getCreatedAt() == null) {
            placeSummary.setCreatedAt(new Date());
        }
        if (placeSummary.getPrivacyLevel() == null) {
            placeSummary.setPrivacyLevel(PrivacyMode.PUBLIC);
        }
        return placeSummaryRepository.save(placeSummary);
    }

    @Override
    public Optional<PlaceSummary> getPlaceSummary(Long placeId) {
        return placeSummaryRepository.findById(placeId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByTrip(Long tripId) {
        return placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByTripOrderByDuration(Long tripId) {
        return placeSummaryRepository.findByTripIdOrderByDurationSecDesc(tripId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByCity(Long tripId, String city) {
        return placeSummaryRepository.findByTripIdAndCity(tripId, city);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByDistrict(Long tripId, String district) {
        return placeSummaryRepository.findByTripIdAndDistrict(tripId, district);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesWithCover(Long tripId) {
        return placeSummaryRepository.findByTripIdAndPhotoCoverIdIsNotNull(tripId);
    }

    @Override
    public List<PlaceSummary> getLongStayPlaces(Long tripId, Long minDurationSec) {
        return placeSummaryRepository.findByTripIdAndDurationSecGreaterThanEqual(tripId, minDurationSec);
    }

    @Override
    public List<PlaceSummary> searchByPoiName(Long tripId, String keyword) {
        return placeSummaryRepository.findByTripIdAndPoiNameContaining(tripId, keyword);
    }

    @Override
    @Transactional
    public PlaceSummary updatePlaceSummary(Long placeId, String poiName, String userNotes, String userTags, PrivacyMode privacyLevel) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("闂備線娼婚梽鍕珶閸℃稑纾婚柨婵嗩槸缁犵儤淇婇娑卞劌婵炶绲介埥澶愬箻瀹曞泦銏ゆ煟鎺抽崝鎴濈暦閻戣В鈧箓骞掗弮鍌ゆХplaceId: " + placeId));

        if (poiName != null) {
            place.setPoiName(poiName);
        }
        if (userNotes != null) {
            place.setUserNotes(userNotes);
        }
        if (userTags != null) {
            place.setUserTags(userTags);
        }
        if (privacyLevel != null) {
            place.setPrivacyLevel(privacyLevel);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public PlaceSummary updateCover(Long placeId, Long photoCoverId, Long videoCoverId) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("闂備線娼婚梽鍕珶閸℃稑纾婚柨婵嗩槸缁犵儤淇婇娑卞劌婵炶绲介埥澶愬箻瀹曞泦銏ゆ煟鎺抽崝鎴濈暦閻戣В鈧箓骞掗弮鍌ゆХplaceId: " + placeId));

        if (photoCoverId != null) {
            place.setPhotoCoverId(photoCoverId);
        }
        if (videoCoverId != null) {
            place.setVideoCoverId(videoCoverId);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public PlaceSummary updateMediaCount(Long placeId, Integer photoCount, Integer videoCount) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("闂備線娼婚梽鍕珶閸℃稑纾婚柨婵嗩槸缁犵儤淇婇娑卞劌婵炶绲介埥澶愬箻瀹曞泦銏ゆ煟鎺抽崝鎴濈暦閻戣В鈧箓骞掗弮鍌ゆХplaceId: " + placeId));

        if (photoCount != null) {
            place.setPhotoCount(photoCount);
        }
        if (videoCount != null) {
            place.setVideoCount(videoCount);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public void deletePlaceSummary(Long placeId) {
        placeSummaryMemberRepository.deleteByPlaceSummaryId(placeId);
        placeSummaryRepository.deleteById(placeId);
    }

    @Override
    @Transactional
    public void deletePlaceSummariesByTrip(Long tripId) {
        placeSummaryMemberRepository.deleteByTripId(tripId);
        placeSummaryRepository.deleteByTripId(tripId);
    }

    @Override
    public long countByTrip(Long tripId) {
        return placeSummaryRepository.countByTripId(tripId);
    }

    @Override
    @Transactional
    public List<PlaceSummary> generatePlaceSummariesForTrip(Long tripId) {
        log.info("[PlaceSummary] start generating place summaries for tripId={}", tripId);

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("鐞涘瞼鈻兼稉宥呯摠閸︻煉绱漷ripId: " + tripId));

        placeSummaryMemberRepository.deleteByTripId(tripId);
        placeSummaryRepository.deleteByTripId(tripId);

        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdAndRenderEligibleOrderByTsAsc(tripId, true);
        List<Anchor> anchors = anchorRepository.findByTripId(tripId);
        Map<Long, Photo> photosById = photoRepository.findByTripId(tripId).stream()
                .collect(Collectors.toMap(Photo::getId, photo -> photo, (left, right) -> left));
        Map<Long, Video> videosById = videoRepository.findByTripId(tripId).stream()
                .collect(Collectors.toMap(Video::getId, video -> video, (left, right) -> left));
        CoordType dominantTrackCoordType = resolveDominantTrackCoordType(trackPoints);

        log.info("[PlaceSummary] tripId={} loaded {} track points and {} anchors for clustering",
                tripId, trackPoints.size(), anchors.size());
        List<ClusterPoint> allPoints = new ArrayList<>();

        for (TrackPoint tp : trackPoints) {
            double[] wgs84 = normalizeTrackPointToWgs84(tp);
            if (wgs84 != null && tp.getTs() != null) {
                allPoints.add(new ClusterPoint(wgs84[0], wgs84[1], tp.getTs(), "TRACK_POINT", tp.getId(), tp.getSpeedMps()));
            }
        }

        for (Anchor anchor : anchors) {
            double[] wgs84 = normalizeAnchorToWgs84(anchor, photosById, videosById, dominantTrackCoordType);
            Long ts = anchor.getMatchedTs() != null ? anchor.getMatchedTs() : anchor.getMediaTs();
            if (wgs84 != null && ts != null) {
                String type = anchor.getPhotoId() != null ? "PHOTO" : "VIDEO";
                Long memberId = anchor.getPhotoId() != null ? anchor.getPhotoId() : anchor.getVideoId();
                allPoints.add(new ClusterPoint(wgs84[0], wgs84[1], ts, type, memberId, null));
            }
        }

        allPoints.sort(Comparator.comparingLong(p -> p.ts));

        List<List<ClusterPoint>> clusters = clusterPoints(allPoints);


        log.info("[PlaceSummary] tripId={} generated {} candidate clusters", tripId, clusters.size());
        List<PlaceSummary> result = new ArrayList<>();

        for (List<ClusterPoint> cluster : clusters) {
            if (!isMeaningfulStayCluster(cluster)) {
                continue;
            }

            PlaceSummary placeSummary = createPlaceSummaryFromCluster(trip, cluster);
            placeSummary = placeSummaryRepository.save(placeSummary);

            List<PlaceSummaryMemberPayload> members = buildMembersFromCluster(cluster);
            addMembersToPlaceSummary(placeSummary.getId(), members);

            updatePlaceSummaryStats(placeSummary.getId(), cluster);

            enrichPoiInfo(placeSummary);

            result.add(placeSummary);
        }

        log.info("[PlaceSummary] tripId={} finalized {} place summaries", tripId, result.size());
        return result;
    }

    @Override
    @Async
    public void generatePlaceSummariesForTripAsync(Long tripId) {
        try {
            generatePlaceSummariesForTrip(tripId);
        } catch (Exception e) {
            log.error("[PlaceSummary] 閻庢鍠栭崐褰掝敆閻愮儤鍋ㄩ柣鏃傤焾閻忓洭鏌涢敂钘夘棆闁稿缍侀獮妤€螣濞嗙偓鐭楁繝銏″劶缁墽鎲? tripId={}", tripId, e);
        }
    }

    @Override
    @Transactional
    public void refreshSemanticInfoForTrip(Long tripId) {
        if (tripId == null) {
            return;
        }
        for (PlaceSummary placeSummary : placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId)) {
            if (needsSemanticEnrichment(placeSummary)) {
                enrichPoiInfo(placeSummary);
            }
        }
    }

    @Override
    @Transactional
    public void addMemberToPlaceSummary(Long placeSummaryId, String memberType, Long memberId, String memberRole, Float score) {
        PlaceSummaryMember member = new PlaceSummaryMember();
        member.setPlaceSummaryId(placeSummaryId);
        member.setMemberType(memberType);
        member.setMemberId(memberId);
        member.setMemberRole(memberRole);
        member.setScore(score);
        placeSummaryMemberRepository.save(member);
    }

    @Override
    @Transactional
    public void addMembersToPlaceSummary(Long placeSummaryId, List<PlaceSummaryMemberPayload> members) {
        PlaceSummary placeSummary = placeSummaryRepository.findById(placeSummaryId)
                .orElseThrow(() -> new RuntimeException("闂備線娼婚梽鍕珶閸℃稑纾婚柨婵嗩槸缁犵儤淇婇娑卞劌婵炶绲介埥澶愬箻瀹曞泦銏ゆ煟鎺抽崝鎴濈暦? " + placeSummaryId));

        int sortIndex = 0;
        for (PlaceSummaryMemberPayload payload : members) {
            PlaceSummaryMember member = new PlaceSummaryMember();
            member.setTripId(placeSummary.getTripId());
            member.setPlaceSummaryId(placeSummaryId);
            member.setMemberType(payload.memberType());
            member.setMemberId(payload.memberId());
            member.setMemberRole(payload.memberRole());
            member.setScore(payload.score());
            member.setSortIndex(sortIndex++);
            placeSummaryMemberRepository.save(member);
        }
    }

    @Override
    public List<PlaceSummaryMemberPayload> getPlaceSummaryMembers(Long placeSummaryId) {
        return placeSummaryMemberRepository.findByPlaceSummaryIdOrderBySortIndexAscIdAsc(placeSummaryId)
                .stream()
                .map(m -> new PlaceSummaryMemberPayload(
                        m.getMemberType(),
                        m.getMemberId(),
                        m.getMemberRole(),
                        m.getScore(),
                        m.getSortIndex()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<PlaceSummary> findNearestPlaceSummary(Long tripId, double lat, double lng, double radiusMeters) {
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);

        PlaceSummary nearest = null;
        double minDist = Double.MAX_VALUE;

        for (PlaceSummary place : places) {
            double placeLat = decodeDouble(place.getCenterLatEnc());
            double placeLng = decodeDouble(place.getCenterLngEnc());
            double dist = GeoUtils.haversineMeters(lat, lng, placeLat, placeLng);

            if (dist <= radiusMeters && dist < minDist) {
                minDist = dist;
                nearest = place;
            }
        }

        return Optional.ofNullable(nearest);
    }

    @Override
    @Transactional
    public void updatePoiInfo(Long placeSummaryId, String city, String district, String poiName) {
        PlaceSummary place = placeSummaryRepository.findById(placeSummaryId)
                .orElseThrow(() -> new RuntimeException("闂備線娼婚梽鍕珶閸℃稑纾婚柨婵嗩槸缁犵儤淇婇娑卞劌婵炶绲介埥澶愬箻瀹曞泦銏ゆ煟鎺抽崝鎴濈暦? " + placeSummaryId));

        if (city != null) {
            place.setCity(city);
        }
        if (district != null) {
            place.setDistrict(district);
        }
        if (poiName != null) {
            place.setPoiName(poiName);
        }
        place.setUpdatedAt(new Date());
        placeSummaryRepository.save(place);
    }

    private List<List<ClusterPoint>> clusterPoints(List<ClusterPoint> points) {
        List<List<ClusterPoint>> clusters = new ArrayList<>();
        if (points.isEmpty()) {
            return clusters;
        }

        List<ClusterPoint> currentCluster = new ArrayList<>();
        ClusterPoint prevPoint = null;
        double sumLat = 0.0;
        double sumLng = 0.0;

        for (ClusterPoint point : points) {
            if (prevPoint == null) {
                currentCluster.add(point);
                sumLat = point.lat;
                sumLng = point.lng;
            } else {
                double dist = GeoUtils.haversineMeters(prevPoint.lat, prevPoint.lng, point.lat, point.lng);
                double centerLat = sumLat / currentCluster.size();
                double centerLng = sumLng / currentCluster.size();
                double distToCentroid = GeoUtils.haversineMeters(centerLat, centerLng, point.lat, point.lng);
                long timeGap = point.ts - prevPoint.ts;

                if (dist <= CLUSTER_RADIUS_METERS
                        && distToCentroid <= CLUSTER_CENTROID_RADIUS_METERS
                        && timeGap <= MAX_GAP_SEC * 1000L) {
                    currentCluster.add(point);
                    sumLat += point.lat;
                    sumLng += point.lng;
                } else {
                    if (!currentCluster.isEmpty()) {
                        clusters.add(new ArrayList<>(currentCluster));
                    }
                    currentCluster = new ArrayList<>();
                    currentCluster.add(point);
                    sumLat = point.lat;
                    sumLng = point.lng;
                }
            }
            prevPoint = point;
        }

        if (!currentCluster.isEmpty()) {
            clusters.add(currentCluster);
        }

        return clusters;
    }

    private PlaceSummary createPlaceSummaryFromCluster(Trip trip, List<ClusterPoint> cluster) {
        PlaceSummary place = new PlaceSummary();
        place.setUserId(trip.getUserId());
        place.setTripId(trip.getId());

        List<ClusterPoint> centerPoints = cluster.stream()
                .filter(ClusterPoint::isTrackPoint)
                .collect(Collectors.toList());
        if (centerPoints.isEmpty()) {
            centerPoints = cluster;
        }

        double sumLat = 0, sumLng = 0;
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;

        for (ClusterPoint p : cluster) {
            minTs = Math.min(minTs, p.ts);
            maxTs = Math.max(maxTs, p.ts);
        }

        for (ClusterPoint p : centerPoints) {
            sumLat += p.lat;
            sumLng += p.lng;
        }

        double centerLat = sumLat / centerPoints.size();
        double centerLng = sumLng / centerPoints.size();

        place.setCenterLatEnc(encodeDouble(centerLat));
        place.setCenterLngEnc(encodeDouble(centerLng));
        place.setStartTime(new Date(minTs));
        place.setEndTime(new Date(maxTs));
        place.setDurationSec((maxTs - minTs) / 1000L);
        place.setCity(FALLBACK_CITY);
        place.setDistrict(FALLBACK_DISTRICT);
        place.setPoiName(FALLBACK_POI_NAME_PREFIX + place.getStartTime().getTime());
        place.setPhotoCount(0);
        place.setVideoCount(0);
        place.setPrivacyLevel(PrivacyMode.PUBLIC);
        place.setGeneratedAt(new Date());
        place.setUpdatedAt(new Date());

        return place;
    }

    private List<PlaceSummaryMemberPayload> buildMembersFromCluster(List<ClusterPoint> cluster) {
        return cluster.stream()
                .map(p -> new PlaceSummaryMemberPayload(
                        p.type,
                        p.memberId,
                        "CORE",
                        1.0f,
                        null
                ))
                .collect(Collectors.toList());
    }

    private void updatePlaceSummaryStats(Long placeSummaryId, List<ClusterPoint> cluster) {
        int photoCount = 0;
        int videoCount = 0;
        Long photoCoverId = null;
        Long videoCoverId = null;

        for (ClusterPoint p : cluster) {
            if ("PHOTO".equals(p.type)) {
                photoCount++;
                if (photoCoverId == null) {
                    photoCoverId = p.memberId;
                }
            } else if ("VIDEO".equals(p.type)) {
                videoCount++;
                if (videoCoverId == null) {
                    videoCoverId = p.memberId;
                }
            }
        }

        if (photoCount > 0 || videoCount > 0) {
            updateCover(placeSummaryId, photoCoverId, videoCoverId);
            updateMediaCount(placeSummaryId, photoCount, videoCount);
        }
    }

    private void enrichPoiInfo(PlaceSummary placeSummary) {
        double lat = decodeDouble(placeSummary.getCenterLatEnc());
        double lng = decodeDouble(placeSummary.getCenterLngEnc());

        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return;
        }

        placeSummary.setGeohash(encodeGeohash(lat, lng, 8));

        try {
            double[] gcj02 = GeoUtils.wgs84ToGcj02(lat, lng);
            Optional<ReverseGeocodingService.ReverseGeocodingResult> result =
                    reverseGeocodingService.reverseGeocode(gcj02[0], gcj02[1]);

            if (result.isPresent()) {
                ReverseGeocodingService.ReverseGeocodingResult geo = result.get();
                if (geo.getDisplayCity() != null && !geo.getDisplayCity().isBlank()) {
                    placeSummary.setCity(geo.getDisplayCity());
                }
                if (geo.district() != null && !geo.district().isBlank()) {
                    placeSummary.setDistrict(geo.district());
                }

                String currentPoiName = placeSummary.getPoiName();
                String poiName = geo.poiName();
                if (!hasMeaningfulPoiName(poiName)) {
                    poiName = geo.getDisplayLocation();
                }
                if (!hasMeaningfulPoiName(poiName)) {
                    poiName = currentPoiName;
                }
                if (poiName != null && !poiName.isBlank()) {
                    placeSummary.setPoiName(poiName.trim());
                }

                String semanticTags = joinSemanticTags(buildSemanticTags(geo, placeSummary.getPoiName()));
                if (semanticTags != null) {
                    placeSummary.setUserTags(semanticTags);
                }
                placeSummary.setUpdatedAt(new Date());
                placeSummaryRepository.save(placeSummary);
                log.debug("[PlaceSummary] enriched poi info: placeId={}, city={}, district={}, poiName={}, tags={}",
                        placeSummary.getId(), placeSummary.getCity(), placeSummary.getDistrict(), placeSummary.getPoiName(), placeSummary.getUserTags());
                return;
            }
        } catch (Exception e) {
            log.warn("[PlaceSummary] reverse geocoding failed: placeId={}, error={}",
                    placeSummary.getId(), e.getMessage());
        }

        if (!hasSemanticTags(placeSummary.getUserTags()) && placeSummary.getPoiName() != null && !placeSummary.getPoiName().isBlank()) {
            placeSummary.setUserTags(joinSemanticTags(buildSemanticTags(null, placeSummary.getPoiName())));
        }
        placeSummary.setUpdatedAt(new Date());
        placeSummaryRepository.save(placeSummary);
    }

    private String joinSemanticTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags.stream().distinct().limit(6).collect(Collectors.toList()));
    }

    private List<String> buildSemanticTags(ReverseGeocodingService.ReverseGeocodingResult geo, String poiName) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addSemanticTags(tags, poiName);
        if (geo != null) {
            addSemanticTags(tags, geo.poiType());
            addSemanticTags(tags, geo.getDisplayLocation());
            addSemanticTags(tags, geo.formattedAddress());
            addSemanticTags(tags, geo.district());
            addSemanticTags(tags, geo.getDisplayCity());
        }
        return new ArrayList<>(tags);
    }

    private boolean isMeaningfulStayCluster(List<ClusterPoint> cluster) {
        if (cluster == null || cluster.size() < MIN_POINTS_FOR_CLUSTER) {
            return false;
        }

        ClusterMetrics metrics = measureCluster(cluster);
        long minDurationSec = metrics.anchorCount > 0 ? MIN_STAY_DURATION_SEC : MIN_TRACK_ONLY_STAY_DURATION_SEC;
        if (metrics.durationSec < minDurationSec) {
            return false;
        }
        if (metrics.trackPointCount == 0 && metrics.anchorCount < MIN_ANCHORS_FOR_STAY) {
            return false;
        }
        if (metrics.maxDistanceToCentroidMeters > MAX_STAY_SPREAD_METERS) {
            return false;
        }
        if (metrics.endToEndMeters > MAX_STAY_END_TO_END_METERS) {
            return false;
        }
        if (metrics.pathLengthMeters > MAX_STAY_PATH_LENGTH_METERS) {
            return false;
        }
        if (metrics.inferredSpeedMps > MAX_STAY_INFERRED_SPEED_MPS) {
            return false;
        }
        return metrics.medianReportedSpeedMps == null || metrics.medianReportedSpeedMps <= MAX_STAY_REPORTED_SPEED_MPS;
    }

    private ClusterMetrics measureCluster(List<ClusterPoint> cluster) {
        List<ClusterPoint> ordered = new ArrayList<>(cluster);
        ordered.sort(Comparator.comparingLong(p -> p.ts));

        List<ClusterPoint> representativePoints = ordered.stream()
                .filter(ClusterPoint::isTrackPoint)
                .collect(Collectors.toList());
        if (representativePoints.isEmpty()) {
            representativePoints = ordered;
        }

        long minTs = ordered.get(0).ts;
        long maxTs = ordered.get(ordered.size() - 1).ts;
        double centerLat = representativePoints.stream().mapToDouble(p -> p.lat).average().orElse(Double.NaN);
        double centerLng = representativePoints.stream().mapToDouble(p -> p.lng).average().orElse(Double.NaN);
        double maxDistanceToCentroidMeters = representativePoints.stream()
                .mapToDouble(p -> GeoUtils.haversineMeters(centerLat, centerLng, p.lat, p.lng))
                .max()
                .orElse(0.0);

        double pathLengthMeters = 0.0;
        for (int i = 1; i < representativePoints.size(); i++) {
            ClusterPoint prev = representativePoints.get(i - 1);
            ClusterPoint current = representativePoints.get(i);
            pathLengthMeters += GeoUtils.haversineMeters(prev.lat, prev.lng, current.lat, current.lng);
        }

        ClusterPoint first = representativePoints.get(0);
        ClusterPoint last = representativePoints.get(representativePoints.size() - 1);
        double endToEndMeters = GeoUtils.haversineMeters(first.lat, first.lng, last.lat, last.lng);
        long durationMs = Math.max(1L, maxTs - minTs);
        double inferredSpeedMps = pathLengthMeters / (durationMs / 1000.0);

        List<Double> reportedSpeeds = representativePoints.stream()
                .map(p -> p.speedMps)
                .filter(Objects::nonNull)
                .map(Float::doubleValue)
                .filter(speed -> Double.isFinite(speed) && speed >= 0.0)
                .sorted()
                .collect(Collectors.toList());
        Double medianReportedSpeed = reportedSpeeds.isEmpty()
                ? null
                : reportedSpeeds.get(reportedSpeeds.size() / 2);

        int trackPointCount = (int) ordered.stream().filter(ClusterPoint::isTrackPoint).count();
        int anchorCount = ordered.size() - trackPointCount;

        return new ClusterMetrics(
                (maxTs - minTs) / 1000L,
                trackPointCount,
                anchorCount,
                maxDistanceToCentroidMeters,
                endToEndMeters,
                pathLengthMeters,
                inferredSpeedMps,
                medianReportedSpeed
        );
    }

    private boolean needsSemanticEnrichment(PlaceSummary placeSummary) {
        if (placeSummary == null) {
            return false;
        }
        if (placeSummary.getGeohash() == null || placeSummary.getGeohash().isBlank()) {
            return true;
        }
        if (!hasMeaningfulPoiName(placeSummary.getPoiName())) {
            return true;
        }
        return !hasSemanticTags(placeSummary.getUserTags());
    }

    private boolean hasSemanticTags(String userTags) {
        return userTags != null && Arrays.stream(userTags.split(","))
                .map(String::trim)
                .anyMatch(tag -> !tag.isEmpty());
    }

    private boolean hasMeaningfulPoiName(String poiName) {
        if (poiName == null || poiName.isBlank()) {
            return false;
        }
        String normalized = poiName.trim();
        return !normalized.matches("(?i)^(\\u5730\\u70b9|\\u505c\\u7559\\u70b9|place)\\s*\\d+$");
    }

    private void addSemanticTags(Set<String> tags, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.trim();
        for (Map.Entry<String, String> entry : SEMANTIC_TAG_KEYWORDS.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                tags.add(entry.getValue());
            }
        }
    }

    private static Map<String, String> buildSemanticTagKeywords() {
        Map<String, String> mapping = new LinkedHashMap<>();

        mapping.put("\u8d85\u5e02", "\u8d85\u5e02");
        mapping.put("\u4fbf\u5229\u5e97", "\u4fbf\u5229\u5e97");
        mapping.put("\u5546\u573a", "\u5546\u573a");
        mapping.put("\u8d2d\u7269", "\u8d2d\u7269");
        mapping.put("\u8d2d\u7269\u670d\u52a1", "\u8d2d\u7269");
        mapping.put("\u751f\u6d3b\u670d\u52a1", "\u751f\u6d3b\u670d\u52a1");

        mapping.put("\u5b66\u6821", "\u5b66\u6821");
        mapping.put("\u5927\u5b66", "\u5b66\u6821");
        mapping.put("\u4e2d\u5b66", "\u5b66\u6821");
        mapping.put("\u5c0f\u5b66", "\u5b66\u6821");
        mapping.put("\u5e7c\u513f\u56ed", "\u5b66\u6821");
        mapping.put("\u79d1\u6559\u6587\u5316\u670d\u52a1", "\u5b66\u6821");

        mapping.put("\u516c\u56ed", "\u516c\u56ed");
        mapping.put("\u690d\u7269\u56ed", "\u516c\u56ed");
        mapping.put("\u52a8\u7269\u56ed", "\u516c\u56ed");
        mapping.put("\u5e7f\u573a", "\u5e7f\u573a");
        mapping.put("\u4f53\u80b2\u4f11\u95f2\u670d\u52a1", "\u516c\u56ed");

        mapping.put("\u6e56", "\u6e56\u6cca");
        mapping.put("\u6d77", "\u6d77\u8fb9");
        mapping.put("\u6c5f", "\u6c5f\u6cb3");
        mapping.put("\u6cb3", "\u6c5f\u6cb3");
        mapping.put("\u6c34\u5e93", "\u6e56\u6cca");
        mapping.put("\u6c99\u6ee9", "\u6d77\u8fb9");
        mapping.put("\u6d77\u6ee9", "\u6d77\u8fb9");
        mapping.put("\u6d77\u6ee8", "\u6d77\u8fb9");

        mapping.put("\u666f\u533a", "\u666f\u533a");
        mapping.put("\u666f\u70b9", "\u666f\u533a");
        mapping.put("\u98ce\u666f\u540d\u80dc", "\u666f\u533a");
        mapping.put("\u98ce\u666f\u540d\u80dc\u76f8\u5173", "\u666f\u533a");
        mapping.put("\u535a\u7269\u9986", "\u535a\u7269\u9986");
        mapping.put("\u7f8e\u672f\u9986", "\u535a\u7269\u9986");
        mapping.put("\u56fe\u4e66\u9986", "\u56fe\u4e66\u9986");

        mapping.put("\u5bfa", "\u5bfa\u5e99");
        mapping.put("\u5e99", "\u5bfa\u5e99");
        mapping.put("\u6559\u5802", "\u6559\u5802");

        mapping.put("\u533b\u9662", "\u533b\u9662");
        mapping.put("\u533b\u7597\u4fdd\u5065\u670d\u52a1", "\u533b\u9662");

        mapping.put("\u5730\u94c1", "\u4ea4\u901a\u67a2\u7ebd");
        mapping.put("\u706b\u8f66\u7ad9", "\u4ea4\u901a\u67a2\u7ebd");
        mapping.put("\u9ad8\u94c1\u7ad9", "\u4ea4\u901a\u67a2\u7ebd");
        mapping.put("\u673a\u573a", "\u4ea4\u901a\u67a2\u7ebd");
        mapping.put("\u6c7d\u8f66\u7ad9", "\u4ea4\u901a\u67a2\u7ebd");
        mapping.put("\u4ea4\u901a\u8bbe\u65bd\u670d\u52a1", "\u4ea4\u901a\u67a2\u7ebd");

        mapping.put("\u9910\u5385", "\u9910\u996e");
        mapping.put("\u996d\u5e97", "\u9910\u996e");
        mapping.put("\u5496\u5561", "\u5496\u5561\u9986");
        mapping.put("\u5496\u5561\u9986", "\u5496\u5561\u9986");
        mapping.put("\u9910\u996e\u670d\u52a1", "\u9910\u996e");

        mapping.put("\u6c11\u5bbf", "\u4f4f\u5bbf");
        mapping.put("\u9152\u5e97", "\u4f4f\u5bbf");
        mapping.put("\u5bbe\u9986", "\u4f4f\u5bbf");
        mapping.put("\u9732\u8425", "\u9732\u8425");
        mapping.put("\u8425\u5730", "\u9732\u8425");
        mapping.put("\u4f4f\u5bbf\u670d\u52a1", "\u4f4f\u5bbf");
        return mapping;
    }

    private CoordType resolveDominantTrackCoordType(List<TrackPoint> trackPoints) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return CoordType.WGS84;
        }
        Map<CoordType, Long> counts = trackPoints.stream()
                .map(TrackPoint::getRawCoordType)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(type -> type, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(CoordType.WGS84);
    }

    private double[] normalizeTrackPointToWgs84(TrackPoint trackPoint) {
        if (trackPoint == null || trackPoint.getLatEnc() == null || trackPoint.getLngEnc() == null) {
            return null;
        }
        double lat = decodeDouble(trackPoint.getLatEnc());
        double lng = decodeDouble(trackPoint.getLngEnc());
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return null;
        }
        return toWgs84(lat, lng, trackPoint.getRawCoordType());
    }

    private double[] normalizeAnchorToWgs84(Anchor anchor,
                                            Map<Long, Photo> photosById,
                                            Map<Long, Video> videosById,
                                            CoordType dominantTrackCoordType) {
        if (anchor == null || anchor.getLatEnc() == null || anchor.getLngEnc() == null) {
            return null;
        }
        double lat = decodeDouble(anchor.getLatEnc());
        double lng = decodeDouble(anchor.getLngEnc());
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return null;
        }
        return toWgs84(lat, lng, resolveAnchorCoordType(anchor, photosById, videosById, dominantTrackCoordType));
    }

    private CoordType resolveAnchorCoordType(Anchor anchor,
                                             Map<Long, Photo> photosById,
                                             Map<Long, Video> videosById,
                                             CoordType dominantTrackCoordType) {
        if (anchor == null) {
            return dominantTrackCoordType;
        }
        if ("PROJECTED".equalsIgnoreCase(anchor.getProjectionStatus())) {
            return dominantTrackCoordType;
        }
        if (anchor.getPhotoId() != null) {
            return resolvePhotoCoordType(photosById.get(anchor.getPhotoId()), dominantTrackCoordType);
        }
        if (anchor.getVideoId() != null) {
            return resolveVideoCoordType(videosById.get(anchor.getVideoId()), dominantTrackCoordType);
        }
        return dominantTrackCoordType;
    }

    private CoordType resolvePhotoCoordType(Photo photo, CoordType fallback) {
        if (photo == null) {
            return fallback;
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
        return fallback;
    }

    private CoordType resolveVideoCoordType(Video video, CoordType fallback) {
        if (video == null) {
            return fallback;
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
        return fallback;
    }

    private double[] toWgs84(double lat, double lng, CoordType coordType) {
        if (coordType == CoordType.GCJ02) {
            return GeoUtils.gcj02ToWgs84(lat, lng);
        }
        return new double[]{lat, lng};
    }


    private String encodeGeohash(double lat, double lng, int precision) {
        String chars = "0123456789bcdefghjkmnpqrstuvwxyz";
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder hash = new StringBuilder();
        int bit = 0;
        char ch = 0;
        boolean even = true;

        while (hash.length() < precision) {
            double mid;
            if (even) {
                mid = (lngRange[0] + lngRange[1]) / 2.0;
                if (lng >= mid) {
                    ch |= 1 << (4 - bit);
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2.0;
                if (lat >= mid) {
                    ch |= 1 << (4 - bit);
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            even = !even;
            bit++;

            if (bit == 5) {
                hash.append(chars.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }

        return hash.toString();
    }

    private static double decodeDouble(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return Double.NaN;
        long bits = 0L;
        for (int i = 0; i < 8; i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private static byte[] encodeDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((bits >> (i * 8)) & 0xFF);
        }
        return bytes;
    }

    private static final class ClusterPoint {
        final double lat;
        final double lng;
        final long ts;
        final String type;
        final Long memberId;
        final Float speedMps;

        ClusterPoint(double lat, double lng, long ts, String type, Long memberId, Float speedMps) {
            this.lat = lat;
            this.lng = lng;
            this.ts = ts;
            this.type = type;
            this.memberId = memberId;
            this.speedMps = speedMps;
        }

        boolean isTrackPoint() {
            return "TRACK_POINT".equals(type);
        }
    }

    private record ClusterMetrics(long durationSec,
                                  int trackPointCount,
                                  int anchorCount,
                                  double maxDistanceToCentroidMeters,
                                  double endToEndMeters,
                                  double pathLengthMeters,
                                  double inferredSpeedMps,
                                  Double medianReportedSpeedMps) {
    }
}
