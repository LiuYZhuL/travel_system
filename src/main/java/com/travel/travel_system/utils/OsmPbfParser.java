package com.travel.travel_system.utils;

import com.travel.travel_system.dto.GeoPoint;
import com.travel.travel_system.dto.RoadEdge;
import com.travel.travel_system.dto.RoadNetwork;
import com.travel.travel_system.dto.RoadNode;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.pbf2.v0_6.PbfReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 osmosis-core / osmosis-pbf2 的 OSM PBF 解析器。
 *
 * 关键修复：
 * 旧实现先按 bbox 收集 node，再按 node 是否存在来建边，导致大量“只有一端 node 落在 bbox 内”的有效 segment 被裁掉。
 *
 * 新实现改为 4 遍：
 * 1) 收集 bbox+buffer 内的 seed nodes
 * 2) 用 seed nodes 找出相关 vehicle ways，并收集这些 way 的全部 nodeId
 * 3) 再按 nodeId 回填这些 way 所需的全部 node 坐标
 * 4) 最后真正建边，并按 bbox.segmentMayIntersect(...) 做几何裁剪
 */
public class OsmPbfParser {

    private static final Set<String> EXCLUDED_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
            "footway", "cycleway", "path", "steps", "pedestrian", "bridleway",
            "corridor", "platform", "elevator", "proposed"
    ));

    private static final Set<String> MOTOR_VEHICLE_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
            "motorway", "motorway_link",
            "trunk", "trunk_link",
            "primary", "primary_link",
            "secondary", "secondary_link",
            "tertiary", "tertiary_link",
            "unclassified", "residential", "service", "living_street", "track"
    ));

    /**
     * seed node 只用于“命中相关 way”的第一轮粗筛，适当放大即可。
     */
    private static final double NODE_BBOX_BUFFER_KM = 4.0;

    public static RoadNetwork parseFromResource(String resourcePath,
                                                double centerLat,
                                                double centerLon,
                                                double radiusKm) {
        BBox bbox = buildBBox(centerLat, centerLon, radiusKm, NODE_BBOX_BUFFER_KM);

        Map<Long, NodeCoord> seedNodeCache = new HashMap<>(200_000);
        try (InputStream firstPass = openResource(resourcePath)) {
            collectSeedNodes(firstPass, bbox, seedNodeCache);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第一遍(seed nodes)失败: " + resourcePath, e);
        }

        Set<Long> relevantNodeIds = new HashSet<>(Math.max(seedNodeCache.size() * 4, 16_384));
        try (InputStream secondPass = openResource(resourcePath)) {
            collectRelevantWayNodeIds(secondPass, seedNodeCache.keySet(), relevantNodeIds);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第二遍(ways -> node ids)失败: " + resourcePath, e);
        }

        Map<Long, NodeCoord> nodeCache = new HashMap<>(Math.max(relevantNodeIds.size() + 1024, 16_384));
        try (InputStream thirdPass = openResource(resourcePath)) {
            collectRequiredNodes(thirdPass, relevantNodeIds, nodeCache);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第三遍(required nodes)失败: " + resourcePath, e);
        }

        RoadNetwork network = new RoadNetwork();
        try (InputStream fourthPass = openResource(resourcePath)) {
            buildNetwork(fourthPass, bbox, nodeCache, network);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第四遍(build network)失败: " + resourcePath, e);
        }

        network.rebuildAdjacencyList();

        System.out.println("before cache save: nodes=" + network.getNodeCount()
                + ", edges=" + network.getEdgeCount());
        return network;
    }

    private static InputStream openResource(String resourcePath) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("找不到资源文件: " + resourcePath);
        }
        return in;
    }

    private static void collectSeedNodes(InputStream inputStream,
                                         BBox bbox,
                                         Map<Long, NodeCoord> nodeCache) {
        AtomicLong totalNodes = new AtomicLong();
        AtomicLong keptNodes = new AtomicLong();

        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer.getEntity() instanceof Node node) {
                    totalNodes.incrementAndGet();
                    double lat = node.getLatitude();
                    double lon = node.getLongitude();
                    if (bbox.contains(lat, lon)) {
                        nodeCache.put(node.getId(), new NodeCoord(lat, lon));
                        keptNodes.incrementAndGet();
                    }
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();

        System.out.println("[OSM_PBF_NODES] total=" + totalNodes.get()
                + ", keptInBBox=" + keptNodes.get()
                + ", bbox=" + bbox);
    }

    private static void collectRelevantWayNodeIds(InputStream inputStream,
                                                  Set<Long> seedNodeIds,
                                                  Set<Long> relevantNodeIds) {
        AtomicLong totalWays = new AtomicLong();
        AtomicLong vehicleWays = new AtomicLong();
        AtomicLong relevantWays = new AtomicLong();
        AtomicLong collectedRefs = new AtomicLong();
        Map<String, AtomicLong> highwayTypeCounts = new HashMap<>();

        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way)) {
                    return;
                }
                totalWays.incrementAndGet();

                Map<String, String> tags = readTags(way.getTags());
                String highway = tags.get("highway");
                String effectiveHighway = normalizeHighwayType(highway, tags);
                if (effectiveHighway == null || !isVehicleHighway(highway, tags)) {
                    return;
                }
                vehicleWays.incrementAndGet();
                highwayTypeCounts.computeIfAbsent(effectiveHighway, k -> new AtomicLong()).incrementAndGet();

                boolean relevant = false;
                for (WayNode wayNode : way.getWayNodes()) {
                    if (seedNodeIds.contains(wayNode.getNodeId())) {
                        relevant = true;
                        break;
                    }
                }
                if (!relevant) {
                    return;
                }

                relevantWays.incrementAndGet();
                for (WayNode wayNode : way.getWayNodes()) {
                    if (relevantNodeIds.add(wayNode.getNodeId())) {
                        collectedRefs.incrementAndGet();
                    }
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();

        System.out.println("[OSM_PBF_WAYS] totalWays=" + totalWays.get()
                + ", vehicleWays=" + vehicleWays.get()
                + ", relevantWays=" + relevantWays.get()
                + ", requiredNodeIds=" + relevantNodeIds.size()
                + ", uniqueAddedRefs=" + collectedRefs.get());

        if (!highwayTypeCounts.isEmpty()) {
            List<Map.Entry<String, AtomicLong>> entries = new ArrayList<>(highwayTypeCounts.entrySet());
            entries.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
            int limit = Math.min(12, entries.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, AtomicLong> e = entries.get(i);
                System.out.println("[OSM_PBF_WAYS] highwayType=" + e.getKey() + ", ways=" + e.getValue().get());
            }
        }
    }

    private static void collectRequiredNodes(InputStream inputStream,
                                             Set<Long> requiredNodeIds,
                                             Map<Long, NodeCoord> nodeCache) {
        AtomicLong totalNodes = new AtomicLong();
        AtomicLong keptNodes = new AtomicLong();

        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer.getEntity() instanceof Node node) {
                    totalNodes.incrementAndGet();
                    if (requiredNodeIds.contains(node.getId())) {
                        nodeCache.put(node.getId(), new NodeCoord(node.getLatitude(), node.getLongitude()));
                        keptNodes.incrementAndGet();
                    }
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();

        System.out.println("[OSM_PBF_REQUIRED_NODES] required=" + requiredNodeIds.size()
                + ", loaded=" + keptNodes.get()
                + ", totalNodes=" + totalNodes.get());
    }

    private static void buildNetwork(InputStream inputStream,
                                     BBox bbox,
                                     Map<Long, NodeCoord> nodeCache,
                                     RoadNetwork network) {
        AtomicLong edgeIdSeed = new AtomicLong(1L);
        Map<Long, Integer> degreeCounter = new HashMap<>();
        AtomicLong totalWays = new AtomicLong();
        AtomicLong vehicleWays = new AtomicLong();
        AtomicLong totalSegments = new AtomicLong();
        AtomicLong keptSegments = new AtomicLong();
        AtomicLong missingNodeSegments = new AtomicLong();
        AtomicLong outsideBBoxSegments = new AtomicLong();
        AtomicLong bridgeEdges = new AtomicLong();
        AtomicLong tunnelEdges = new AtomicLong();
        AtomicLong layeredEdges = new AtomicLong();
        AtomicLong rampEdges = new AtomicLong();
        Map<String, AtomicLong> highwayTypeCounts = new HashMap<>();

        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}

            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way)) {
                    return;
                }
                totalWays.incrementAndGet();

                Map<String, String> tags = readTags(way.getTags());
                String highway = tags.get("highway");
                String effectiveHighway = normalizeHighwayType(highway, tags);
                if (effectiveHighway == null || !isVehicleHighway(highway, tags)) {
                    return;
                }
                vehicleWays.incrementAndGet();
                highwayTypeCounts.computeIfAbsent(effectiveHighway, k -> new AtomicLong()).incrementAndGet();

                boolean oneWay = isOneWay(tags);
                int maxSpeed = parseMaxSpeed(tags.get("maxspeed"));
                String name = tags.getOrDefault("name", "未命名道路");
                int layerLevel = parseLayer(tags);
                boolean bridge = isBridge(tags);
                boolean tunnel = isTunnel(tags);
                boolean rampLike = isRampLike(highway, tags);

                List<WayNode> wayNodes = way.getWayNodes();
                for (int i = 0; i < wayNodes.size() - 1; i++) {
                    totalSegments.incrementAndGet();
                    long startNodeId = wayNodes.get(i).getNodeId();
                    long endNodeId = wayNodes.get(i + 1).getNodeId();
                    NodeCoord start = nodeCache.get(startNodeId);
                    NodeCoord end = nodeCache.get(endNodeId);
                    if (start == null || end == null) {
                        missingNodeSegments.incrementAndGet();
                        continue;
                    }

                    if (!bbox.segmentMayIntersect(start.lat, start.lon, end.lat, end.lon)) {
                        outsideBBoxSegments.incrementAndGet();
                        continue;
                    }

                    RoadNode startNode = new RoadNode();
                    startNode.setId(startNodeId);
                    startNode.setLat(start.lat);
                    startNode.setLon(start.lon);
                    network.addNode(startNode);

                    RoadNode endNode = new RoadNode();
                    endNode.setId(endNodeId);
                    endNode.setLat(end.lat);
                    endNode.setLon(end.lon);
                    network.addNode(endNode);

                    RoadEdge edge = new RoadEdge(edgeIdSeed.getAndIncrement(), start.lat, start.lon, end.lat, end.lon);
                    edge.setStartNodeId(startNodeId);
                    edge.setEndNodeId(endNodeId);
                    edge.setSourceWayId(way.getId());
                    edge.setSegmentIndex(i);
                    edge.setName(name);
                    edge.setType(effectiveHighway);
                    edge.setOneWay(oneWay);
                    edge.setMaxSpeed(maxSpeed);
                    edge.setLayerLevel(layerLevel);
                    edge.setBridge(bridge);
                    edge.setTunnel(tunnel);
                    edge.setRampLike(rampLike);
                    edge.setShapePoints(Arrays.asList(
                            new GeoPoint(start.lat, start.lon),
                            new GeoPoint(end.lat, end.lon)
                    ));
                    edge.refreshGeometry();
                    network.addEdge(edge);
                    keptSegments.incrementAndGet();
                    if (bridge) bridgeEdges.incrementAndGet();
                    if (tunnel) tunnelEdges.incrementAndGet();
                    if (layerLevel != 0) layeredEdges.incrementAndGet();
                    if (rampLike) rampEdges.incrementAndGet();

                    degreeCounter.merge(startNodeId, 1, Integer::sum);
                    degreeCounter.merge(endNodeId, 1, Integer::sum);
                }
            }

            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();

        System.out.println("[OSM_PBF_BUILD] totalWays=" + totalWays.get()
                + ", vehicleWays=" + vehicleWays.get()
                + ", totalSegments=" + totalSegments.get()
                + ", keptSegments=" + keptSegments.get()
                + ", missingNodeSegments=" + missingNodeSegments.get()
                + ", outsideBBoxSegments=" + outsideBBoxSegments.get()
                + ", bridgeEdges=" + bridgeEdges.get()
                + ", tunnelEdges=" + tunnelEdges.get()
                + ", layeredEdges=" + layeredEdges.get()
                + ", rampEdges=" + rampEdges.get());

        if (!highwayTypeCounts.isEmpty()) {
            List<Map.Entry<String, AtomicLong>> entries = new ArrayList<>(highwayTypeCounts.entrySet());
            entries.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
            int limit = Math.min(12, entries.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, AtomicLong> e = entries.get(i);
                System.out.println("[OSM_PBF_BUILD] highwayType=" + e.getKey() + ", ways=" + e.getValue().get());
            }
        }

        for (RoadEdge edge : network.getEdges()) {
            int degree = Math.max(
                    degreeCounter.getOrDefault(edge.getStartNodeId(), 0),
                    degreeCounter.getOrDefault(edge.getEndNodeId(), 0)
            );
            edge.setNodeDegree(degree);
            if (!edge.isRampLike()
                    && edge.isOneWay()
                    && edge.getLengthM() <= 220.0
                    && degree >= 3
                    && ("trunk".equals(edge.getType())
                    || "primary".equals(edge.getType())
                    || "secondary".equals(edge.getType())
                    || "unclassified".equals(edge.getType())
                    || "service".equals(edge.getType()))) {
                edge.setRampLike(true);
            }
        }
        for (RoadNode node : new ArrayList<>(network.getEdgesMap().values()).stream()
                .flatMap(edge -> Arrays.stream(new Long[]{edge.getStartNodeId(), edge.getEndNodeId()}))
                .distinct()
                .map(network::getNode)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toList())) {
            node.setDegree(degreeCounter.getOrDefault(node.getId(), 0));
        }
    }

    private static boolean isVehicleHighway(String highway, Map<String, String> tags) {
        if (highway == null) {
            return false;
        }

        String effectiveHighway = normalizeHighwayType(highway, tags);
        if (effectiveHighway == null) {
            return false;
        }
        if (EXCLUDED_HIGHWAY_TYPES.contains(effectiveHighway)) {
            return false;
        }
        if (!MOTOR_VEHICLE_HIGHWAY_TYPES.contains(effectiveHighway)) {
            return false;
        }

        String motorVehicle = tags.get("motor_vehicle");
        if ("no".equalsIgnoreCase(motorVehicle)) {
            return false;
        }
        String access = tags.get("access");
        if ("no".equalsIgnoreCase(access) && !"destination".equalsIgnoreCase(tags.get("service"))) {
            return false;
        }
        return true;
    }

    private static String normalizeHighwayType(String highway, Map<String, String> tags) {
        if (highway == null || highway.isBlank()) {
            return null;
        }
        String normalized = highway.trim();
        if (!"construction".equalsIgnoreCase(normalized)) {
            return normalized;
        }

        String construction = tags.get("construction");
        if (construction == null || construction.isBlank()) {
            return null;
        }
        String subtype = construction.trim();
        if (!MOTOR_VEHICLE_HIGHWAY_TYPES.contains(subtype)) {
            return null;
        }
        return subtype;
    }

    private static Map<String, String> readTags(Collection<Tag> tags) {
        Map<String, String> map = new HashMap<>();
        for (Tag tag : tags) {
            map.put(tag.getKey(), tag.getValue());
        }
        return map;
    }

    private static int parseLayer(Map<String, String> tags) {
        String value = tags.get("layer");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isBridge(Map<String, String> tags) {
        String value = tags.get("bridge");
        return value != null && !"no".equalsIgnoreCase(value);
    }

    private static boolean isTunnel(Map<String, String> tags) {
        String value = tags.get("tunnel");
        return value != null && !"no".equalsIgnoreCase(value);
    }

    private static boolean isRampLike(String highway, Map<String, String> tags) {
        if (highway != null && highway.endsWith("_link")) {
            return true;
        }
        String junction = tags.get("junction");
        return junction != null && ("slip_road".equalsIgnoreCase(junction) || "ramp".equalsIgnoreCase(junction));
    }

    private static boolean isOneWay(Map<String, String> tags) {
        String value = tags.get("oneway");
        return "yes".equalsIgnoreCase(value)
                || "1".equals(value)
                || "true".equalsIgnoreCase(value);
    }

    private static int parseMaxSpeed(String maxspeed) {
        if (maxspeed == null || maxspeed.isBlank()) {
            return 50;
        }
        String digits = maxspeed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 50;
        }
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 50;
        }
    }

    private static BBox buildBBox(double centerLat, double centerLon, double radiusKm, double extraKm) {
        double totalKm = radiusKm + extraKm;
        double latRadius = totalKm / 111.0;
        double lonRadius = totalKm / Math.max(1e-6, 111.0 * Math.cos(Math.toRadians(centerLat)));
        return new BBox(centerLat - latRadius, centerLat + latRadius, centerLon - lonRadius, centerLon + lonRadius);
    }

    private record NodeCoord(double lat, double lon) {}

    private record BBox(double minLat, double maxLat, double minLon, double maxLon) {
        boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }

        boolean segmentMayIntersect(double lat1, double lon1, double lat2, double lon2) {
            double segMinLat = Math.min(lat1, lat2);
            double segMaxLat = Math.max(lat1, lat2);
            double segMinLon = Math.min(lon1, lon2);
            double segMaxLon = Math.max(lon1, lon2);
            return !(segMaxLat < minLat || segMinLat > maxLat || segMaxLon < minLon || segMinLon > maxLon);
        }
    }
}
