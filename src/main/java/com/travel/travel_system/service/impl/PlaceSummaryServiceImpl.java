package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.*;
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

    private static final double CLUSTER_RADIUS_METERS = 50.0;
    private static final long MIN_STAY_DURATION_SEC = 60L;
    private static final long MAX_GAP_SEC = 300L;
    private static final int MIN_POINTS_FOR_CLUSTER = 3;

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
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

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
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

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
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

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
        log.info("[PlaceSummary] 开始为行程 {} 生成地点摘要", tripId);

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在: " + tripId));

        placeSummaryMemberRepository.deleteByTripId(tripId);
        placeSummaryRepository.deleteByTripId(tripId);

        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdAndRenderEligibleOrderByTsAsc(tripId, true);
        List<Anchor> anchors = anchorRepository.findByTripId(tripId);

        log.info("[PlaceSummary] 行程 {} 共有 {} 个轨迹点, {} 个锚点", tripId, trackPoints.size(), anchors.size());

        List<ClusterPoint> allPoints = new ArrayList<>();

        for (TrackPoint tp : trackPoints) {
            double lat = decodeDouble(tp.getLatEnc());
            double lng = decodeDouble(tp.getLngEnc());
            if (Double.isFinite(lat) && Double.isFinite(lng)) {
                allPoints.add(new ClusterPoint(lat, lng, tp.getTs(), "TRACK_POINT", tp.getId()));
            }
        }

        for (Anchor anchor : anchors) {
            double lat = decodeDouble(anchor.getLatEnc());
            double lng = decodeDouble(anchor.getLngEnc());
            Long ts = anchor.getMatchedTs() != null ? anchor.getMatchedTs() : anchor.getMediaTs();
            if (Double.isFinite(lat) && Double.isFinite(lng) && ts != null) {
                String type = anchor.getPhotoId() != null ? "PHOTO" : "VIDEO";
                Long memberId = anchor.getPhotoId() != null ? anchor.getPhotoId() : anchor.getVideoId();
                allPoints.add(new ClusterPoint(lat, lng, ts, type, memberId));
            }
        }

        allPoints.sort(Comparator.comparingLong(p -> p.ts));

        List<List<ClusterPoint>> clusters = clusterPoints(allPoints);

        log.info("[PlaceSummary] 行程 {} 共生成 {} 个聚类", tripId, clusters.size());

        List<PlaceSummary> result = new ArrayList<>();

        for (List<ClusterPoint> cluster : clusters) {
            if (cluster.size() < MIN_POINTS_FOR_CLUSTER) {
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

        log.info("[PlaceSummary] 行程 {} 最终生成 {} 个地点摘要", tripId, result.size());
        return result;
    }

    @Override
    @Async
    public void generatePlaceSummariesForTripAsync(Long tripId) {
        try {
            generatePlaceSummariesForTrip(tripId);
        } catch (Exception e) {
            log.error("[PlaceSummary] 异步生成地点摘要失败: tripId={}", tripId, e);
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
                .orElseThrow(() -> new RuntimeException("地点摘要不存在: " + placeSummaryId));

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
                .orElseThrow(() -> new RuntimeException("地点摘要不存在: " + placeSummaryId));

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

        for (ClusterPoint point : points) {
            if (prevPoint == null) {
                currentCluster.add(point);
            } else {
                double dist = GeoUtils.haversineMeters(prevPoint.lat, prevPoint.lng, point.lat, point.lng);
                long timeGap = point.ts - prevPoint.ts;

                if (dist <= CLUSTER_RADIUS_METERS && timeGap <= MAX_GAP_SEC * 1000L) {
                    currentCluster.add(point);
                } else {
                    if (!currentCluster.isEmpty()) {
                        clusters.add(new ArrayList<>(currentCluster));
                    }
                    currentCluster = new ArrayList<>();
                    currentCluster.add(point);
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

        double sumLat = 0, sumLng = 0;
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;

        for (ClusterPoint p : cluster) {
            sumLat += p.lat;
            sumLng += p.lng;
            minTs = Math.min(minTs, p.ts);
            maxTs = Math.max(maxTs, p.ts);
        }

        double centerLat = sumLat / cluster.size();
        double centerLng = sumLng / cluster.size();

        place.setCenterLatEnc(encodeDouble(centerLat));
        place.setCenterLngEnc(encodeDouble(centerLng));
        place.setStartTime(new Date(minTs));
        place.setEndTime(new Date(maxTs));
        place.setDurationSec((maxTs - minTs) / 1000L);
        place.setGeneratedAt(new Date());

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

        try {
            Optional<ReverseGeocodingService.ReverseGeocodingResult> result = 
                reverseGeocodingService.reverseGeocode(lat, lng);

            if (result.isPresent()) {
                ReverseGeocodingService.ReverseGeocodingResult geo = result.get();
                placeSummary.setCity(geo.getDisplayCity());
                placeSummary.setDistrict(geo.district());
                
                String poiName = geo.poiName();
                if (poiName == null || poiName.isEmpty()) {
                    poiName = geo.getDisplayLocation();
                }
                placeSummary.setPoiName(poiName);

                String geohash = encodeGeohash(lat, lng, 8);
                placeSummary.setGeohash(geohash);

                placeSummaryRepository.save(placeSummary);

                log.debug("[PlaceSummary] 补充POI信息: placeId={}, city={}, district={}, poiName={}", 
                    placeSummary.getId(), geo.getDisplayCity(), geo.district(), poiName);
            }
        } catch (Exception e) {
            log.warn("[PlaceSummary] 逆地理编码失败: placeId={}, error={}", 
                placeSummary.getId(), e.getMessage());
        }
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

        ClusterPoint(double lat, double lng, long ts, String type, Long memberId) {
            this.lat = lat;
            this.lng = lng;
            this.ts = ts;
            this.type = type;
            this.memberId = memberId;
        }
    }
}
