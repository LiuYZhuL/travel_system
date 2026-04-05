package com.travel.travel_system.service.pub;

import com.travel.travel_system.dto.RoadNetwork;
import com.travel.travel_system.utils.OsmPbfParser;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoadNetworkService {

    private static final String CHINA_OSM_RESOURCE_PATH = "china-260317.osm.pbf";
    private static final String PBF_BASE_PATH = "static/pbf/";

    /**
     * 常驻内存缓存大小。
     * 这里先保守给 8 份路网，避免内存打满；后续可按机器内存调到 12 或 16。
     */
    private static final int MAX_MEMORY_CACHE_SIZE = 8;

    /**
     * 内存缓存 key 归一化精度。
     * 和磁盘缓存一样，不需要保留过细的小数位；否则同一片区域会因为中心点轻微抖动命中不了缓存。
     */
    private static final double LAT_LON_GRID = 0.01D;
    private static final double RADIUS_GRID = 0.5D;
    private static final double SPARSE_RETRY_RADIUS_FACTOR = 1.8D;
    private static final int URBAN_SPARSE_EDGE_THRESHOLD = 2000;
    private static final int URBAN_SPARSE_NODE_THRESHOLD = 1500;

    /**
     * 常驻内存缓存：先查内存，再查磁盘，再查 PBF。
     */
    private final Map<String, RoadNetwork> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<String, RoadNetwork>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RoadNetwork> eldest) {
                    return size() > MAX_MEMORY_CACHE_SIZE;
                }
            });

    /**
     * 同一个区域只允许一个线程真正去加载，避免并发请求重复反序列化/重复解析。
     */
    private final ConcurrentHashMap<String, Object> loadingLocks = new ConcurrentHashMap<>();

    private static class ProvinceBounds {
        double minLat;
        double maxLat;
        double minLon;
        double maxLon;
        String fileName;

        ProvinceBounds(double minLat, double maxLat, double minLon, double maxLon, String fileName) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
            this.fileName = fileName;
        }
    }

    private static final Map<String, ProvinceBounds> PROVINCE_BOUNDS = new HashMap<>();

    static {
        PROVINCE_BOUNDS.put("anhui", new ProvinceBounds(29.4, 34.6, 114.9, 119.6, "anhui-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("beijing", new ProvinceBounds(39.4, 41.1, 115.4, 117.5, "beijing-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("chongqing", new ProvinceBounds(28.1, 32.2, 105.3, 110.2, "chongqing-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("fujian", new ProvinceBounds(23.3, 28.2, 115.8, 120.8, "fujian-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("gansu", new ProvinceBounds(32.3, 42.8, 92.2, 108.8, "gansu-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("guangdong", new ProvinceBounds(20.1, 25.5, 109.7, 117.5, "guangdong-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("guangxi", new ProvinceBounds(20.9, 26.4, 104.4, 112.1, "guangxi-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("guizhou", new ProvinceBounds(24.3, 29.2, 103.6, 109.6, "guizhou-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("hainan", new ProvinceBounds(18.1, 20.5, 108.5, 111.3, "hainan-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("hebei", new ProvinceBounds(36.0, 42.7, 113.5, 119.9, "hebei-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("heilongjiang", new ProvinceBounds(43.2, 53.6, 121.2, 135.1, "heilongjiang-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("henan", new ProvinceBounds(31.4, 36.4, 110.4, 116.6, "henan-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("hubei", new ProvinceBounds(29.0, 33.3, 108.2, 116.1, "hubei-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("hunan", new ProvinceBounds(24.6, 30.1, 108.8, 114.7, "hunan-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("inner-mongolia", new ProvinceBounds(37.4, 53.2, 97.2, 126.3, "inner-mongolia-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("jiangsu", new ProvinceBounds(30.7, 35.1, 116.4, 121.9, "jiangsu-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("jiangxi", new ProvinceBounds(24.3, 30.1, 113.4, 118.7, "jiangxi-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("jilin", new ProvinceBounds(40.9, 46.4, 121.1, 131.3, "jilin-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("liaoning", new ProvinceBounds(38.4, 43.5, 118.5, 125.8, "liaoning-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("ningxia", new ProvinceBounds(35.2, 39.5, 104.2, 107.0, "ningxia-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("qinghai", new ProvinceBounds(31.3, 39.4, 89.4, 103.2, "qinghai-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("shaanxi", new ProvinceBounds(31.0, 39.6, 105.5, 111.3, "shaanxi-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("shandong", new ProvinceBounds(34.3, 38.5, 114.8, 122.7, "shandong-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("shanghai", new ProvinceBounds(30.7, 31.5, 121.0, 122.0, "shanghai-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("shanxi", new ProvinceBounds(34.5, 40.8, 110.2, 114.7, "shanxi-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("sichuan", new ProvinceBounds(26.0, 34.4, 97.3, 108.6, "sichuan-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("tianjin", new ProvinceBounds(38.6, 40.3, 116.7, 118.1, "tianjin-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("tibet", new ProvinceBounds(26.3, 36.5, 78.3, 99.1, "tibet-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("xinjiang", new ProvinceBounds(34.2, 49.2, 73.5, 96.4, "xinjiang-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("yunnan", new ProvinceBounds(21.1, 29.2, 97.4, 106.2, "yunnan-260321.osm.pbf"));
        PROVINCE_BOUNDS.put("zhejiang", new ProvinceBounds(27.1, 31.3, 118.0, 122.9, "zhejiang-260321.osm.pbf"));
    }

    private String findProvinceFile(double lat, double lon) {
        for (Map.Entry<String, ProvinceBounds> entry : PROVINCE_BOUNDS.entrySet()) {
            ProvinceBounds bounds = entry.getValue();
            if (lat >= bounds.minLat && lat <= bounds.maxLat &&
                    lon >= bounds.minLon && lon <= bounds.maxLon) {
                return bounds.fileName;
            }
        }
        return null;
    }

    public RoadNetwork getRoadNetwork(double lat, double lon, double radiusKm) {

        System.out.println("RoadNetworkService.getRoadNetwork lat=" + lat + ", lon=" + lon + ", radiusKm=" + radiusKm);
        System.out.println("[ROAD_NETWORK_LOAD] request center=(" + lat + "," + lon + ") radiusKm=" + radiusKm);
        return loadFromPbf(lat, lon, radiusKm);


//        String cacheKey = buildCacheKey(lat, lon, radiusKm);
//
//        RoadNetwork memoryHit = getFromMemory(cacheKey);
//        if (memoryHit != null) {
//            return memoryHit;
//        }
//
//        Object lock = loadingLocks.computeIfAbsent(cacheKey, k -> new Object());
//        synchronized (lock) {
//            try {
//                // 双重检查，避免并发情况下重复加载
//                memoryHit = getFromMemory(cacheKey);
//                if (memoryHit != null) {
//                    return memoryHit;
//                }
//
//                if (RoadNetworkSerializer.cacheExists(lat, lon, radiusKm)) {
//                    RoadNetwork cached = RoadNetworkSerializer.loadFromCache(lat, lon, radiusKm);
//                    System.out.println("after cache load: nodes=" + cached.getNodeCount()
//                            + ", edges=" + cached.getEdgeCount());
//                    if (cached != null) {
//                        putIntoMemory(cacheKey, cached);
//                        return cached;
//                    }
//                }
//
//                RoadNetwork roadNetwork = loadFromPbf(lat, lon, radiusKm);
//                if (roadNetwork != null) {
//                    RoadNetworkSerializer.saveToCache(roadNetwork, lat, lon, radiusKm);
//                    putIntoMemory(cacheKey, roadNetwork);
//                }
//                return roadNetwork;
//            } finally {
//                loadingLocks.remove(cacheKey, lock);
//            }
//        }
    }

    /**
     * 手动预热内存缓存，可在应用启动后或固定区域高频访问前调用。
     */
    public void warmUp(double lat, double lon, double radiusKm) {
        getRoadNetwork(lat, lon, radiusKm);
    }

    /**
     * 手动清理常驻内存缓存，调试或内存紧张时可调用。
     */
    public void clearMemoryCache() {
        synchronized (memoryCache) {
            memoryCache.clear();
        }
        loadingLocks.clear();
    }

    /**
     * 便于观察当前常驻了多少份路网。
     */
    public int getMemoryCacheSize() {
        synchronized (memoryCache) {
            return memoryCache.size();
        }
    }

    private RoadNetwork loadFromPbf(double lat, double lon, double radiusKm) {
        RoadNetwork roadNetwork = null;
        String provinceFile = findProvinceFile(lat, lon);

        if (provinceFile != null) {
            System.out.println("检测到省份区域，尝试加载省份路网: " + provinceFile);
            System.out.println("[ROAD_NETWORK_LOAD] provinceFile=" + provinceFile + " center=(" + lat + "," + lon + ") radiusKm=" + radiusKm);
            try {
                roadNetwork = OsmPbfParser.parseFromResource(PBF_BASE_PATH + provinceFile, lat, lon, radiusKm);
                if (roadNetwork != null) {
                    System.out.println("[ROAD_NETWORK_LOAD] province result nodes=" + roadNetwork.getNodeCount() + ", edges=" + roadNetwork.getEdgeCount());
                }
                if (isSparseUrbanNetwork(roadNetwork, radiusKm)) {
                    double retryRadiusKm = radiusKm * SPARSE_RETRY_RADIUS_FACTOR;
                    System.out.println("省份路网过稀，扩大半径重试: " + retryRadiusKm + "km");
                    RoadNetwork retried = OsmPbfParser.parseFromResource(PBF_BASE_PATH + provinceFile, lat, lon, retryRadiusKm);
                    if (retried != null) {
                        System.out.println("[ROAD_NETWORK_LOAD] province retry result nodes=" + retried.getNodeCount() + ", edges=" + retried.getEdgeCount());
                    }
                    if (retried != null && retried.getEdgeCount() > roadNetwork.getEdgeCount()) {
                        roadNetwork = retried;
                    }
                }
            } catch (Exception e) {
                System.err.println("加载省份路网失败: " + e.getMessage());
                roadNetwork = null;
            }
        }

        if (roadNetwork == null || roadNetwork.getEdgeCount() == 0 || isSparseUrbanNetwork(roadNetwork, radiusKm)) {
            System.out.println("省份路网未找到/为空/过稀，加载全国路网");
            System.out.println("[ROAD_NETWORK_LOAD] fallback=national radiusKm=" + (radiusKm * SPARSE_RETRY_RADIUS_FACTOR));
            RoadNetwork national = OsmPbfParser.parseFromResource(CHINA_OSM_RESOURCE_PATH, lat, lon, radiusKm * SPARSE_RETRY_RADIUS_FACTOR);
            if (national != null) {
                System.out.println("[ROAD_NETWORK_LOAD] national result nodes=" + national.getNodeCount() + ", edges=" + national.getEdgeCount());
            }
            if (national != null && (roadNetwork == null || national.getEdgeCount() >= roadNetwork.getEdgeCount())) {
                roadNetwork = national;
            }
        }

        if (roadNetwork != null) {
            System.out.println("最终路网: nodes=" + roadNetwork.getNodeCount() + ", edges=" + roadNetwork.getEdgeCount());
            System.out.println("[ROAD_NETWORK_LOAD] final nodes=" + roadNetwork.getNodeCount() + ", edges=" + roadNetwork.getEdgeCount() + ", sparse=" + isSparseUrbanNetwork(roadNetwork, radiusKm));
        }
        return roadNetwork;
    }

    private boolean isSparseUrbanNetwork(RoadNetwork roadNetwork, double radiusKm) {
        if (roadNetwork == null) {
            return true;
        }
        if (radiusKm < 2.0) {
            return false;
        }
        return roadNetwork.getEdgeCount() < URBAN_SPARSE_EDGE_THRESHOLD
                || roadNetwork.getNodeCount() < URBAN_SPARSE_NODE_THRESHOLD;
    }

    private RoadNetwork getFromMemory(String cacheKey) {
        synchronized (memoryCache) {
            return memoryCache.get(cacheKey);
        }
    }

    private void putIntoMemory(String cacheKey, RoadNetwork roadNetwork) {
        if (roadNetwork == null) {
            return;
        }
        synchronized (memoryCache) {
            memoryCache.put(cacheKey, roadNetwork);
        }
    }

    private String buildCacheKey(double lat, double lon, double radiusKm) {
        double normalizedLat = normalize(lat, LAT_LON_GRID);
        double normalizedLon = normalize(lon, LAT_LON_GRID);
        double normalizedRadius = normalize(radiusKm, RADIUS_GRID);
        return normalizedLat + "_" + normalizedLon + "_" + normalizedRadius;
    }

    private double normalize(double value, double grid) {
        return Math.round(value / grid) * grid;
    }
}
