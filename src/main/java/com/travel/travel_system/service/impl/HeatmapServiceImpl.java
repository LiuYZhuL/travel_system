package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.service.HeatmapService;
import com.travel.travel_system.vo.HeatmapPointVO;
import com.travel.travel_system.vo.UserHeatmapVO;
import com.travel.travel_system.vo.enums.HeatmapScopeVO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final double MAX_ACCEPTABLE_ACCURACY_M = 150.0;
    private static final double STAY_DISTANCE_M = 30.0;

    private final TrackPointRepository trackPointRepository;

    private final Map<String, CacheEntry<UserHeatmapVO>> userCache = synchronizedLruMap(USER_CACHE_MAX_ENTRIES);
    private final Map<String, CacheEntry<List<HeatmapPointVO>>> tripCache = synchronizedLruMap(TRIP_CACHE_MAX_ENTRIES);

    public HeatmapServiceImpl(TrackPointRepository trackPointRepository) {
        this.trackPointRepository = trackPointRepository;
    }

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
        List<TrackPoint> sourcePoints = trackPointRepository.findByUserIdAndTimeRange(
                userId,
                timeRange.startTs,
                timeRange.endTs
        );

        List<HeatmapPointVO> heatPoints = aggregateHeatmapPoints(sourcePoints, normalizedGrid);
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

        List<TrackPoint> sourcePoints = trackPointRepository.findByTripIdOrderByTsAsc(tripId);
        List<HeatmapPointVO> heatPoints = aggregateHeatmapPoints(sourcePoints, normalizedGrid);

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
                    double dist = calculateGreatCircleDistance(prev.lat, prev.lng, point.lat, point.lng);
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
            result.add(HeatmapPointVO.builder()
                    .lat(round6(cell.centerLat))
                    .lng(round6(cell.centerLng))
                    .weight(weight)
                    .build());
        }

        result.sort((a, b) -> Integer.compare(
                b.getWeight() != null ? b.getWeight() : 0,
                a.getWeight() != null ? a.getWeight() : 0
        ));

        if (result.size() > MAX_OUTPUT_POINTS) {
            return new ArrayList<>(result.subList(0, MAX_OUTPUT_POINTS));
        }
        return result;
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

            long ts = point.getTs();
            if (ts == lastTs && !decoded.isEmpty()) {
                DecodedPoint prev = decoded.get(decoded.size() - 1);
                if (calculateGreatCircleDistance(prev.lat, prev.lng, lat, lng) < 3.0) {
                    continue;
                }
            }

            decoded.add(new DecodedPoint(point.getTripId(), ts, lat, lng));
            lastTs = ts;
        }
        return decoded;
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
                    .build());
        }
        return copied;
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

    private double calculateGreatCircleDistance(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);

        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return 6_371_000.0 * c;
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
