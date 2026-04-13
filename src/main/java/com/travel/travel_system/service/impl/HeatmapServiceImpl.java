package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.service.HeatmapService;
import com.travel.travel_system.service.ReverseGeocodingService;
import com.travel.travel_system.utils.GeoUtils;
import com.travel.travel_system.vo.HeatmapPointVO;
import com.travel.travel_system.vo.UserHeatmapVO;
import com.travel.travel_system.vo.enums.HeatmapScopeVO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Transactional(readOnly = true)
public class HeatmapServiceImpl implements HeatmapService {

    private static final Logger log = LoggerFactory.getLogger(HeatmapServiceImpl.class);

    private static final int DEFAULT_GRID_METERS = 80;
    private static final int MIN_GRID_METERS = 20;
    private static final int MAX_GRID_METERS = 500;
    private static final int MAX_SOURCE_POINTS = 50000;
    private static final int MAX_OUTPUT_POINTS = 600;
    private static final long MAX_DT_SEC = 300L;
    private static final long CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final int USER_CACHE_MAX_ENTRIES = 64;
    private static final int TRIP_CACHE_MAX_ENTRIES = 128;
    private static final int MAX_SEMANTIC_POINTS = 24;
    private static final double MAX_ACCEPTABLE_ACCURACY_M = 150.0;
    private static final double STAY_DISTANCE_M = 30.0;
    private static final double SEMANTIC_MERGE_DISTANCE_M = 280.0;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private AnchorRepository anchorRepository;

    @Autowired
    private ReverseGeocodingService reverseGeocodingService;

    private final Map<String, CacheEntry<UserHeatmapVO>> userCache = synchronizedLruMap(USER_CACHE_MAX_ENTRIES);
    private final Map<String, CacheEntry<List<HeatmapPointVO>>> tripCache = synchronizedLruMap(TRIP_CACHE_MAX_ENTRIES);


    @PostConstruct
    void init() {
        log.info("HeatmapService 初始化完成，默认网格={}m", DEFAULT_GRID_METERS);
    }

    @Override
    public UserHeatmapVO buildUserHeatmap(Long userId, String scope, Integer gridMeters) {
        validateUserId(userId);
        int normalizedGrid = normalizeGridMeters(gridMeters);
        String normalizedScope = normalizeScope(scope);
        String cacheKey = buildUserCacheKey(userId, normalizedScope, normalizedGrid);

        CacheEntry<UserHeatmapVO> cached = userCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return deepCopy(cached.value);
        }

        TimeRange timeRange = resolveTimeRange(normalizedScope);
        List<TrackPoint> trackPoints = trackPointRepository.findByUserIdAndTimeRange(
                userId,
                timeRange.startTs,
                timeRange.endTs
        );

        List<Anchor> anchors = anchorRepository.findByUserIdAndProjectionStatusInWithCoords(
                userId,
                List.of("PROJECTED", "MANUAL_FIXED")
        );

        List<DecodedPoint> mergedPoints = mergeTrackPointsAndAnchors(trackPoints, anchors);
        List<HeatmapPointVO> heatPoints = aggregateHeatmapPointsFromDecoded(mergedPoints, normalizedGrid);

        UserHeatmapVO result = UserHeatmapVO.builder()
                .userId(userId)
                .scope(resolveScopeEnum(normalizedScope))
                .points(heatPoints)
                .build();

        userCache.put(cacheKey, new CacheEntry<>(deepCopy(result), System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return result;
    }

    @Override
    public List<HeatmapPointVO> buildTripHeatmap(Long tripId, Integer gridMeters) {
        validateTripId(tripId);
        int normalizedGrid = normalizeGridMeters(gridMeters);
        String cacheKey = buildTripCacheKey(tripId, normalizedGrid);

        CacheEntry<List<HeatmapPointVO>> cached = tripCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return deepCopyPoints(cached.value);
        }

        List<TrackPoint> trackPoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        List<Anchor> anchors = anchorRepository.findByTripIdAndProjectionStatusInWithCoords(
                tripId,
                List.of("PROJECTED", "MANUAL_FIXED")
        );

        List<DecodedPoint> mergedPoints = mergeTrackPointsAndAnchors(trackPoints, anchors);
        List<HeatmapPointVO> heatPoints = aggregateHeatmapPointsFromDecoded(mergedPoints, normalizedGrid);

        tripCache.put(cacheKey, new CacheEntry<>(deepCopyPoints(heatPoints), System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return heatPoints;
    }

    @Override
    public void evictUserHeatmap(Long userId) {
        if (userId == null) {
            return;
        }
        userCache.keySet().removeIf(key -> key.startsWith("user:" + userId + ":"));
    }

    @Override
    public void evictTripHeatmap(Long tripId) {
        if (tripId == null) {
            return;
        }
        tripCache.keySet().removeIf(key -> key.startsWith("trip:" + tripId + ":"));
    }


    private List<DecodedPoint> mergeTrackPointsAndAnchors(List<TrackPoint> trackPoints, List<Anchor> anchors) {
        List<DecodedPoint> result = new ArrayList<>();

        if (trackPoints != null) {
            for (TrackPoint point : trackPoints) {
                if (point == null || point.getLatEnc() == null || point.getLngEnc() == null || point.getTs() == null) {
                    continue;
                }
                if (point.getAccuracyM() != null && point.getAccuracyM() > MAX_ACCEPTABLE_ACCURACY_M) {
                    continue;
                }

                double lat = bytesToDouble(point.getLatEnc());
                double lng = bytesToDouble(point.getLngEnc());

                if (!isValidCoordinate(lat, lng)) {
                    continue;
                }

                double normalizedLat;
                double normalizedLng;

                if (point.getRawCoordType() == com.travel.travel_system.model.enums.CoordType.GCJ02) {
                    double[] converted = GeoUtils.gcj02ToWgs84(lat, lng);
                    normalizedLat = converted[0];
                    normalizedLng = converted[1];
                } else {
                    normalizedLat = lat;
                    normalizedLng = lng;
                }

                result.add(new DecodedPoint(point.getTripId(), point.getTs(), normalizedLat, normalizedLng));
            }
        }

        if (anchors != null) {
            for (Anchor anchor : anchors) {
                if (anchor.getLatEnc() == null || anchor.getLngEnc() == null) {
                    continue;
                }

                Long ts = anchor.getMatchedTs() != null ? anchor.getMatchedTs() : anchor.getMediaTs();
                if (ts == null) {
                    continue;
                }

                double lat = bytesToDouble(anchor.getLatEnc());
                double lng = bytesToDouble(anchor.getLngEnc());

                if (!isValidCoordinate(lat, lng)) {
                    continue;
                }

                result.add(new DecodedPoint(anchor.getTripId(), ts, lat, lng));
            }
        }

        result.sort(Comparator.comparingLong(p -> p.ts));
        return thinIfNecessary(result);
    }


    private List<HeatmapPointVO> aggregateHeatmapPointsFromDecoded(List<DecodedPoint> points, int gridMeters) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, HeatCell> cells = new LinkedHashMap<>();
        DecodedPoint prev = null;

        for (DecodedPoint point : points) {
            GridIndex grid = toGrid(point.lat, point.lng, gridMeters);
            String key = grid.key();
            HeatCell cell = cells.computeIfAbsent(key, k -> new HeatCell(grid.centerLat(gridMeters), grid.centerLng(gridMeters)));

            cell.addPoint(point);
            cell.sampleCount += 1;
            cell.weightScore += 1.0;
            if (point.tripId != null) {
                cell.tripIds.add(point.tripId);
            }

            if (prev != null) {
                long dtSec = Math.max(0L, (point.ts - prev.ts) / 1000L);
                if (dtSec > 0) {
                    dtSec = Math.min(dtSec, MAX_DT_SEC);
                    double dist = GeoUtils.haversineMeters(prev.lat, prev.lng, point.lat, point.lng);
                    if (dist <= STAY_DISTANCE_M) {
                        cell.staySec += dtSec;
                        cell.weightScore += Math.min(6.0, dtSec / 20.0);
                    } else {
                        cell.weightScore += Math.min(2.0, dtSec / 120.0);
                    }
                }
            }

            prev = point;
        }

        List<HeatmapPointVO> result = new ArrayList<>(cells.size());
        for (HeatCell cell : cells.values()) {
            int weight = normalizeWeight(cell);
            if (weight <= 0) {
                continue;
            }
            double[] displayCoord = GeoUtils.wgs84ToGcj02(cell.centerLat, cell.centerLng);
            result.add(HeatmapPointVO.builder()
                    .lat(round6(displayCoord[0]))
                    .lng(round6(displayCoord[1]))
                    .weight(weight)
                    .coordType("GCJ02")
                    .build());
        }

        result.sort((a, b) -> Integer.compare(
                b.getWeight() != null ? b.getWeight() : 0,
                a.getWeight() != null ? a.getWeight() : 0
        ));

        List<HeatmapPointVO> limited = result.size() > MAX_OUTPUT_POINTS
                ? new ArrayList<>(result.subList(0, MAX_OUTPUT_POINTS))
                : result;
        enrichSemanticLabels(limited);
        List<HeatmapPointVO> merged = mergeSemanticHotspots(limited);
        merged.sort((a, b) -> Integer.compare(
                b.getWeight() != null ? b.getWeight() : 0,
                a.getWeight() != null ? a.getWeight() : 0
        ));
        return merged;
    }


    private List<HeatmapPointVO> aggregateHeatmapPoints(List<TrackPoint> rawPoints, int gridMeters) {
        if (rawPoints == null || rawPoints.isEmpty()) {
            return Collections.emptyList();
        }

        List<DecodedPoint> points = decodeAndClean(rawPoints);
        if (points.isEmpty()) {
            return Collections.emptyList();
        }

        points.sort(Comparator.comparingLong(p -> p.ts));
        points = thinIfNecessary(points);

        Map<String, HeatCell> cells = new LinkedHashMap<>();
        DecodedPoint prev = null;

        for (DecodedPoint point : points) {
            GridIndex grid = toGrid(point.lat, point.lng, gridMeters);
            String key = grid.key();
            HeatCell cell = cells.computeIfAbsent(key, k -> new HeatCell(grid.centerLat(gridMeters), grid.centerLng(gridMeters)));

            cell.addPoint(point);
            cell.sampleCount += 1;
            cell.weightScore += 1.0;
            if (point.tripId != null) {
                cell.tripIds.add(point.tripId);
            }

            if (prev != null) {
                long dtSec = Math.max(0L, (point.ts - prev.ts) / 1000L);
                if (dtSec > 0) {
                    dtSec = Math.min(dtSec, MAX_DT_SEC);
                    double dist = GeoUtils.haversineMeters(prev.lat, prev.lng, point.lat, point.lng);
                    if (dist <= STAY_DISTANCE_M) {
                        cell.staySec += dtSec;
                        cell.weightScore += Math.min(6.0, dtSec / 20.0);
                    } else {
                        cell.weightScore += Math.min(2.0, dtSec / 120.0);
                    }
                }
            }

            prev = point;
        }

        List<HeatmapPointVO> result = new ArrayList<>(cells.size());
        for (HeatCell cell : cells.values()) {
            int weight = normalizeWeight(cell);
            if (weight <= 0) {
                continue;
            }
            double[] displayCoord = GeoUtils.wgs84ToGcj02(cell.centerLat, cell.centerLng);
            result.add(HeatmapPointVO.builder()
                    .lat(round6(displayCoord[0]))
                    .lng(round6(displayCoord[1]))
                    .weight(weight)
                    .coordType("GCJ02")
                    .build());
        }

        result.sort((a, b) -> Integer.compare(
                b.getWeight() != null ? b.getWeight() : 0,
                a.getWeight() != null ? a.getWeight() : 0
        ));

        List<HeatmapPointVO> limited = result.size() > MAX_OUTPUT_POINTS
                ? new ArrayList<>(result.subList(0, MAX_OUTPUT_POINTS))
                : result;
        enrichSemanticLabels(limited);
        List<HeatmapPointVO> merged = mergeSemanticHotspots(limited);
        merged.sort((a, b) -> Integer.compare(
                b.getWeight() != null ? b.getWeight() : 0,
                a.getWeight() != null ? a.getWeight() : 0
        ));
        return merged;
    }

    private List<DecodedPoint> decodeAndClean(List<TrackPoint> rawPoints) {
        List<DecodedPoint> decoded = new ArrayList<>(rawPoints.size());
        long lastTs = Long.MIN_VALUE;
        for (TrackPoint point : rawPoints) {
            if (point == null || point.getLatEnc() == null || point.getLngEnc() == null || point.getTs() == null) {
                continue;
            }
            if (point.getAccuracyM() != null && point.getAccuracyM() > MAX_ACCEPTABLE_ACCURACY_M) {
                continue;
            }

            double lat = bytesToDouble(point.getLatEnc());
            double lng = bytesToDouble(point.getLngEnc());

            if (!isValidCoordinate(lat, lng)) {
                continue;
            }

            // 统一归一到 WGS84 做聚合，最终输出再转成 GCJ02 给前端和高德语义使用。
            double normalizedLat;
            double normalizedLng;

            if (point.getRawCoordType() == com.travel.travel_system.model.enums.CoordType.GCJ02) {
                double[] converted = GeoUtils.gcj02ToWgs84(lat, lng);
                normalizedLat = converted[0];
                normalizedLng = converted[1];
            } else {
                normalizedLat = lat;
                normalizedLng = lng;
            }

            long ts = point.getTs();
            if (ts == lastTs && !decoded.isEmpty()) {
                DecodedPoint prev = decoded.get(decoded.size() - 1);
                if (GeoUtils.haversineMeters(prev.lat, prev.lng, normalizedLat, normalizedLng) < 3.0) {
                    continue;
                }
            }

            decoded.add(new DecodedPoint(point.getTripId(), ts, normalizedLat, normalizedLng));
            lastTs = ts;
        }
        return decoded;
    }

    private void enrichSemanticLabels(List<HeatmapPointVO> points) {
        if (points == null || points.isEmpty()) {
            return;
        }

        int enrichCount = Math.min(MAX_SEMANTIC_POINTS, points.size());
        for (int i = 0; i < enrichCount; i++) {
            HeatmapPointVO point = points.get(i);
            if (point == null || point.getLat() == null || point.getLng() == null) {
                continue;
            }
            try {
                reverseGeocodingService.reverseGeocode(point.getLat(), point.getLng())
                        .ifPresent(result -> applySemanticFields(point, result));
            } catch (Exception e) {
                log.debug("Heatmap reverse geocode failed, lat={}, lng={}, error={}",
                        point.getLat(), point.getLng(), e.getMessage());
            }
        }
    }

    private void applySemanticFields(HeatmapPointVO point, ReverseGeocodingService.ReverseGeocodingResult result) {
        if (point == null || result == null) {
            return;
        }
        point.setSemanticTitle(firstNonBlank(
                result.poiName(),
                result.getDisplayLocation(),
                result.formattedAddress(),
                result.district(),
                result.getDisplayCity()
        ));
        point.setSemanticAddress(firstNonBlank(
                joinAddress(result.getDisplayCity(), result.district(), joinAddress(result.street(), result.streetNumber())),
                result.formattedAddress(),
                joinAddress(result.province(), result.getDisplayCity(), result.district())
        ));
        point.setCity(blankToNull(result.getDisplayCity()));
        point.setDistrict(blankToNull(result.district()));
    }

    private List<HeatmapPointVO> mergeSemanticHotspots(List<HeatmapPointVO> points) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        List<HeatmapPointVO> merged = new ArrayList<>();
        for (HeatmapPointVO point : points) {
            if (point == null) {
                continue;
            }
            String mergeKey = buildSemanticMergeKey(point);
            HeatmapPointVO target = null;
            if (mergeKey != null) {
                for (HeatmapPointVO candidate : merged) {
                    if (!Objects.equals(buildSemanticMergeKey(candidate), mergeKey)) {
                        continue;
                    }
                    if (candidate.getLat() == null || candidate.getLng() == null
                            || point.getLat() == null || point.getLng() == null) {
                        continue;
                    }
                    double distance = GeoUtils.haversineMeters(
                            candidate.getLat(), candidate.getLng(),
                            point.getLat(), point.getLng()
                    );
                    if (distance <= SEMANTIC_MERGE_DISTANCE_M) {
                        target = candidate;
                        break;
                    }
                }
            }

            if (target == null) {
                merged.add(copyPoint(point));
                continue;
            }

            mergePointInto(target, point);
        }
        return merged;
    }

    private String buildSemanticMergeKey(HeatmapPointVO point) {
        if (point == null) {
            return null;
        }
        String title = normalizeSemanticKey(firstNonBlank(point.getSemanticTitle(), point.getSemanticAddress()));
        if (title == null) {
            return null;
        }
        String region = normalizeSemanticKey(firstNonBlank(point.getDistrict(), point.getCity()));
        return region == null ? title : title + "|" + region;
    }

    private String normalizeSemanticKey(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        return normalized
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private HeatmapPointVO copyPoint(HeatmapPointVO source) {
        if (source == null) {
            return null;
        }
        return HeatmapPointVO.builder()
                .lat(source.getLat())
                .lng(source.getLng())
                .weight(source.getWeight())
                .coordType(source.getCoordType())
                .semanticTitle(source.getSemanticTitle())
                .semanticAddress(source.getSemanticAddress())
                .city(source.getCity())
                .district(source.getDistrict())
                .build();
    }

    private void mergePointInto(HeatmapPointVO target, HeatmapPointVO incoming) {
        if (target == null || incoming == null) {
            return;
        }

        int targetWeight = target.getWeight() != null ? target.getWeight() : 0;
        int incomingWeight = incoming.getWeight() != null ? incoming.getWeight() : 0;
        int totalWeight = targetWeight + incomingWeight;

        if (totalWeight > 0
                && target.getLat() != null && target.getLng() != null
                && incoming.getLat() != null && incoming.getLng() != null) {
            double mergedLat = ((target.getLat() * targetWeight) + (incoming.getLat() * incomingWeight)) / totalWeight;
            double mergedLng = ((target.getLng() * targetWeight) + (incoming.getLng() * incomingWeight)) / totalWeight;
            target.setLat(round6(mergedLat));
            target.setLng(round6(mergedLng));
        }

        target.setWeight(totalWeight);
        target.setCoordType(firstNonBlank(target.getCoordType(), incoming.getCoordType(), "GCJ02"));
        target.setSemanticTitle(firstNonBlank(target.getSemanticTitle(), incoming.getSemanticTitle()));
        target.setSemanticAddress(firstNonBlank(target.getSemanticAddress(), incoming.getSemanticAddress()));
        target.setCity(firstNonBlank(target.getCity(), incoming.getCity()));
        target.setDistrict(firstNonBlank(target.getDistrict(), incoming.getDistrict()));
    }

    private List<DecodedPoint> thinIfNecessary(List<DecodedPoint> points) {
        if (points.size() <= MAX_SOURCE_POINTS) {
            return points;
        }
        int step = (int) Math.ceil(points.size() / (double) MAX_SOURCE_POINTS);
        List<DecodedPoint> thinned = new ArrayList<>(MAX_SOURCE_POINTS + 4);
        for (int i = 0; i < points.size(); i += step) {
            thinned.add(points.get(i));
        }
        if (!Objects.equals(thinned.get(thinned.size() - 1), points.get(points.size() - 1))) {
            thinned.add(points.get(points.size() - 1));
        }
        return thinned;
    }

    private int normalizeWeight(HeatCell cell) {
        double tripBonus = cell.tripIds.size() * 2.0;
        double stayBonus = Math.min(20.0, cell.staySec / 30.0);
        double sampleBonus = Math.min(50.0, cell.sampleCount * 0.8);
        int weight = (int) Math.round(cell.weightScore + tripBonus + stayBonus + sampleBonus);
        return Math.max(1, Math.min(100, weight));
    }

    private GridIndex toGrid(double lat, double lng, int gridMeters) {
        double latDegreeMeters = 111_320.0;
        double lngDegreeMeters = Math.max(1.0, 111_320.0 * Math.cos(Math.toRadians(lat)));

        double latStep = gridMeters / latDegreeMeters;
        double lngStep = gridMeters / lngDegreeMeters;

        long latIndex = (long) Math.floor(lat / latStep);
        long lngIndex = (long) Math.floor(lng / lngStep);
        return new GridIndex(latIndex, lngIndex, latStep, lngStep);
    }

    private boolean isValidCoordinate(double lat, double lng) {
        return !Double.isNaN(lat)
                && !Double.isNaN(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0
                && !(Math.abs(lat) < 1e-9 && Math.abs(lng) < 1e-9);
    }

    private String buildUserCacheKey(Long userId, String scope, int gridMeters) {
        return "user:" + userId + ':' + scope + ':' + gridMeters;
    }

    private String buildTripCacheKey(Long tripId, int gridMeters) {
        return "trip:" + tripId + ':' + gridMeters;
    }

    private int normalizeGridMeters(Integer gridMeters) {
        if (gridMeters == null) {
            return DEFAULT_GRID_METERS;
        }
        return Math.max(MIN_GRID_METERS, Math.min(MAX_GRID_METERS, gridMeters));
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 非法");
        }
    }

    private void validateTripId(Long tripId) {
        if (tripId == null || tripId <= 0) {
            throw new IllegalArgumentException("tripId 非法");
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL";
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ALL", "GLOBAL", "TOTAL" -> "ALL";
            case "YEAR", "Y", "ANNUAL" -> "YEAR";
            case "MONTH", "M" -> "MONTH";
            case "WEEK", "W" -> "WEEK";
            default -> "ALL";
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private HeatmapScopeVO resolveScopeEnum(String normalizedScope) {
        try {
            return (HeatmapScopeVO) Enum.valueOf((Class<? extends Enum>) HeatmapScopeVO.class, normalizedScope);
        } catch (Exception ignore) {
            // 兼容枚举命名与当前实现不完全一致的情况
            try {
                return (HeatmapScopeVO) HeatmapScopeVO.class.getEnumConstants()[0];
            } catch (Exception e) {
                log.warn("HeatmapScopeVO 解析失败，返回 null，scope={}", normalizedScope);
                return null;
            }
        }
    }

    private TimeRange resolveTimeRange(String normalizedScope) {
        long now = System.currentTimeMillis();
        return switch (normalizedScope) {
            case "WEEK" -> new TimeRange(now - TimeUnit.DAYS.toMillis(7), now);
            case "MONTH" -> new TimeRange(now - TimeUnit.DAYS.toMillis(31), now);
            case "YEAR" -> new TimeRange(now - TimeUnit.DAYS.toMillis(366), now);
            default -> new TimeRange(0L, now + TimeUnit.DAYS.toMillis(1));
        };
    }

    private UserHeatmapVO deepCopy(UserHeatmapVO source) {
        if (source == null) {
            return null;
        }
        return UserHeatmapVO.builder()
                .userId(source.getUserId())
                .scope(source.getScope())
                .points(deepCopyPoints(source.getPoints()))
                .build();
    }

    private List<HeatmapPointVO> deepCopyPoints(List<HeatmapPointVO> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<HeatmapPointVO> copied = new ArrayList<>(source.size());
        for (HeatmapPointVO point : source) {
            if (point == null) {
                continue;
            }
            copied.add(HeatmapPointVO.builder()
                    .lat(point.getLat())
                    .lng(point.getLng())
                    .weight(point.getWeight())
                    .coordType(point.getCoordType())
                    .semanticTitle(point.getSemanticTitle())
                    .semanticAddress(point.getSemanticAddress())
                    .city(point.getCity())
                    .district(point.getDistrict())
                    .build());
        }
        return copied;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String joinAddress(String... parts) {
        if (parts == null || parts.length == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String normalized = blankToNull(part);
            if (normalized == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(normalized);
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
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

    private static <K, V> Map<K, V> synchronizedLruMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final long expireAt;

        private CacheEntry(T value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private static final class TimeRange {
        private final long startTs;
        private final long endTs;

        private TimeRange(long startTs, long endTs) {
            this.startTs = startTs;
            this.endTs = endTs;
        }
    }

    private static final class DecodedPoint {
        private final Long tripId;
        private final long ts;
        private final double lat;
        private final double lng;

        private DecodedPoint(Long tripId, long ts, double lat, double lng) {
            this.tripId = tripId;
            this.ts = ts;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final class HeatCell {
        private final double centerLat;
        private final double centerLng;
        private final Set<Long> tripIds = new LinkedHashSet<>();
        private long sampleCount;
        private long staySec;
        private double weightScore;

        private HeatCell(double centerLat, double centerLng) {
            this.centerLat = centerLat;
            this.centerLng = centerLng;
        }

        private void addPoint(DecodedPoint point) {
            // 当前版本用网格中心做输出即可，先保留接口占位，后续可替换成加权质心。
        }
    }

    private static final class GridIndex {
        private final long latIndex;
        private final long lngIndex;
        private final double latStep;
        private final double lngStep;

        private GridIndex(long latIndex, long lngIndex, double latStep, double lngStep) {
            this.latIndex = latIndex;
            this.lngIndex = lngIndex;
            this.latStep = latStep;
            this.lngStep = lngStep;
        }

        private String key() {
            return latIndex + ":" + lngIndex;
        }

        private double centerLat(int gridMeters) {
            return (latIndex + 0.5d) * latStep;
        }

        private double centerLng(int gridMeters) {
            return (lngIndex + 0.5d) * lngStep;
        }
    }
}
