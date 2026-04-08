package com.travel.travel_system;

import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.pbf2.v0_6.PbfReader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 调试类：检查某个原始 GPS 点附近 200m / 300m 内，PBF 里到底有哪些原始 OSM way。
 *
 * 作用：
 * 1. 判断“附近根本没有路”是不是数据源问题
 * 2. 判断“有路但没进 RoadNetwork”是不是过滤规则问题
 *
 * 运行方式：
 * 直接运行 main()，或者调用 inspectFromResource(...)
 */
public class OsmNearbyWayInspectorTest {

    /**
     * 为了避免 way 的局部节点被 bbox 裁掉，这里给一个较大的缓冲。
     */
    private static final double NODE_BUFFER_KM = 1.5;
    private static Logger log;

    public static void main(String[] args) throws Exception {
        // 你可以直接改这里测试前半段的坏点
        inspectFromResource(
                "static/pbf/henan-260321.osm.pbf",
                34.172665,
                113.812228,
                300.0
        );
    }

    public static void inspectFromResource(String resourcePath,
                                           double centerLat,
                                           double centerLon,
                                           double radiusMeters) throws IOException {
        BBox bbox = buildBBox(centerLat, centerLon, radiusMeters / 1000.0, NODE_BUFFER_KM);
        Map<Long, NodeCoord> nodeCache = new HashMap<>(50_000);

        try (InputStream nodePass = openResource(resourcePath)) {
            collectNodes(nodePass, bbox, nodeCache);
        }

        List<WayHit> hits = new ArrayList<>();
        try (InputStream wayPass = openResource(resourcePath)) {
            collectNearbyWays(wayPass, centerLat, centerLon, radiusMeters, nodeCache, hits);
        }

        hits.sort(Comparator.comparingDouble(WayHit::getMinDistanceMeters)
                .thenComparingLong(WayHit::getWayId));

        log.info("====================================================");
        log.info("[OSM_NEARBY_WAY_INSPECT] center=(" + centerLat + "," + centerLon + ") radiusMeters=" + radiusMeters);
        log.info("[OSM_NEARBY_WAY_INSPECT] nodeCache=" + nodeCache.size() + ", hits=" + hits.size());
        log.info("====================================================");

        if (hits.isEmpty()) {
            log.info("[OSM_NEARBY_WAY_INSPECT] 未找到半径内的原始 OSM way。\n");
            return;
        }

        int limit = Math.min(80, hits.size());
        for (int i = 0; i < limit; i++) {
            WayHit hit = hits.get(i);
            log.info(String.format(
                    Locale.ROOT,
                    "[OSM_NEARBY_WAY_INSPECT] rank=%d dist=%.2fm wayId=%d name=%s highway=%s construction=%s service=%s access=%s motor_vehicle=%s bridge=%s tunnel=%s layer=%s junction=%s oneway=%s ref=%s destination=%s lanes=%s refs=%d",
                    i,
                    hit.getMinDistanceMeters(),
                    hit.getWayId(),
                    safe(hit.getName()),
                    safe(hit.getHighway()),
                    safe(hit.getConstruction()),
                    safe(hit.getService()),
                    safe(hit.getAccess()),
                    safe(hit.getMotorVehicle()),
                    safe(hit.getBridge()),
                    safe(hit.getTunnel()),
                    safe(hit.getLayer()),
                    safe(hit.getJunction()),
                    safe(hit.getOneway()),
                    safe(hit.getRef()),
                    safe(hit.getDestination()),
                    safe(hit.getLanes()),
                    hit.getNodeRefCount()
            ));
        }
        log.info("====================================================");
    }

    private static InputStream openResource(String resourcePath) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("找不到资源文件: " + resourcePath);
        }
        return in;
    }

    private static void collectNodes(InputStream inputStream,
                                     BBox bbox,
                                     Map<Long, NodeCoord> nodeCache) {
        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer.getEntity() instanceof Node node) {
                    double lat = node.getLatitude();
                    double lon = node.getLongitude();
                    if (bbox.contains(lat, lon)) {
                        nodeCache.put(node.getId(), new NodeCoord(lat, lon));
                    }
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private static void collectNearbyWays(InputStream inputStream,
                                          double centerLat,
                                          double centerLon,
                                          double radiusMeters,
                                          Map<Long, NodeCoord> nodeCache,
                                          List<WayHit> hits) {
        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way)) {
                    return;
                }

                Map<String, String> tags = readTags(way.getTags());
                String highway = tags.get("highway");
                if (highway == null) {
                    return;
                }

                List<NodeCoord> coords = new ArrayList<>(way.getWayNodes().size());
                for (WayNode wn : way.getWayNodes()) {
                    NodeCoord coord = nodeCache.get(wn.getNodeId());
                    if (coord != null) {
                        coords.add(coord);
                    }
                }
                if (coords.size() < 2) {
                    return;
                }

                double minDistance = Double.POSITIVE_INFINITY;
                for (int i = 0; i < coords.size() - 1; i++) {
                    NodeCoord a = coords.get(i);
                    NodeCoord b = coords.get(i + 1);
                    double d = distanceToSegmentMeters(centerLat, centerLon, a.lat, a.lon, b.lat, b.lon);
                    minDistance = Math.min(minDistance, d);
                }

                if (minDistance <= radiusMeters) {
                    hits.add(new WayHit(
                            way.getId(),
                            tags.get("name"),
                            highway,
                            tags.get("construction"),
                            tags.get("service"),
                            tags.get("access"),
                            tags.get("motor_vehicle"),
                            tags.get("bridge"),
                            tags.get("tunnel"),
                            tags.get("layer"),
                            tags.get("junction"),
                            tags.get("oneway"),
                            tags.get("ref"),
                            tags.get("destination"),
                            tags.get("lanes"),
                            way.getWayNodes().size(),
                            minDistance
                    ));
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private static Map<String, String> readTags(Collection<Tag> tags) {
        Map<String, String> map = new HashMap<>();
        for (Tag tag : tags) {
            map.put(tag.getKey(), tag.getValue());
        }
        return map;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static BBox buildBBox(double centerLat, double centerLon, double radiusKm, double extraKm) {
        double totalKm = radiusKm + extraKm;
        double latRadius = totalKm / 111.0;
        double lonRadius = totalKm / Math.max(1e-6, 111.0 * Math.cos(Math.toRadians(centerLat)));
        return new BBox(centerLat - latRadius, centerLat + latRadius, centerLon - lonRadius, centerLon + lonRadius);
    }

    private static double distanceToSegmentMeters(double lat,
                                                  double lon,
                                                  double lat1,
                                                  double lon1,
                                                  double lat2,
                                                  double lon2) {
        double meanLatRad = Math.toRadians((lat + lat1 + lat2) / 3.0);
        double meterPerLat = 111_320.0;
        double meterPerLon = Math.cos(meanLatRad) * 111_320.0;

        double px = lon * meterPerLon;
        double py = lat * meterPerLat;
        double x1 = lon1 * meterPerLon;
        double y1 = lat1 * meterPerLat;
        double x2 = lon2 * meterPerLon;
        double y2 = lat2 * meterPerLat;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 0.0) {
            return Math.hypot(px - x1, py - y1);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return Math.hypot(px - projX, py - projY);
    }

    private record NodeCoord(double lat, double lon) {}

    private record BBox(double minLat, double maxLat, double minLon, double maxLon) {
        boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }

    private static class WayHit {
        private final long wayId;
        private final String name;
        private final String highway;
        private final String construction;
        private final String service;
        private final String access;
        private final String motorVehicle;
        private final String bridge;
        private final String tunnel;
        private final String layer;
        private final String junction;
        private final String oneway;
        private final String ref;
        private final String destination;
        private final String lanes;
        private final int nodeRefCount;
        private final double minDistanceMeters;

        private WayHit(long wayId,
                       String name,
                       String highway,
                       String construction,
                       String service,
                       String access,
                       String motorVehicle,
                       String bridge,
                       String tunnel,
                       String layer,
                       String junction,
                       String oneway,
                       String ref,
                       String destination,
                       String lanes,
                       int nodeRefCount,
                       double minDistanceMeters) {
            this.wayId = wayId;
            this.name = name;
            this.highway = highway;
            this.construction = construction;
            this.service = service;
            this.access = access;
            this.motorVehicle = motorVehicle;
            this.bridge = bridge;
            this.tunnel = tunnel;
            this.layer = layer;
            this.junction = junction;
            this.oneway = oneway;
            this.ref = ref;
            this.destination = destination;
            this.lanes = lanes;
            this.nodeRefCount = nodeRefCount;
            this.minDistanceMeters = minDistanceMeters;
        }

        public long getWayId() { return wayId; }
        public String getName() { return name; }
        public String getHighway() { return highway; }
        public String getConstruction() { return construction; }
        public String getService() { return service; }
        public String getAccess() { return access; }
        public String getMotorVehicle() { return motorVehicle; }
        public String getBridge() { return bridge; }
        public String getTunnel() { return tunnel; }
        public String getLayer() { return layer; }
        public String getJunction() { return junction; }
        public String getOneway() { return oneway; }
        public String getRef() { return ref; }
        public String getDestination() { return destination; }
        public String getLanes() { return lanes; }
        public int getNodeRefCount() { return nodeRefCount; }
        public double getMinDistanceMeters() { return minDistanceMeters; }
    }
}
