package com.travel.travel_system.utils;

import com.travel.travel_system.model.dto.GeoPoint;
import com.travel.travel_system.model.dto.RoadEdge;
import com.travel.travel_system.model.dto.RoadNetwork;
import com.travel.travel_system.model.dto.RoadNode;
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
 * 核心改动：按相邻 OSM shape node 切边，而不是把整条 way 粗化成一条直线边。
 */
public class OsmPbfParser {

    /**
     * 改为“排除明显非机动车/非通行道路”，避免立交连接道因 highway 类型不在白名单而被过滤掉。
     */
    private static final Set<String> EXCLUDED_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
            "footway", "cycleway", "path", "steps", "pedestrian", "bridleway",
            "corridor", "platform", "elevator", "proposed", "construction"
    ));

    private static final double NODE_BBOX_BUFFER_KM = 4.0;

    public static RoadNetwork parseFromResource(String resourcePath,
                                                double centerLat,
                                                double centerLon,
                                                double radiusKm) {
        BBox bbox = buildBBox(centerLat, centerLon, radiusKm, NODE_BBOX_BUFFER_KM);

        Map<Long, NodeCoord> nodeCache = new HashMap<>(200_000);
        try (InputStream firstPass = openResource(resourcePath)) {
            collectNodes(firstPass, bbox, nodeCache);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第一遍失败: " + resourcePath, e);
        }

        RoadNetwork network = new RoadNetwork();
        try (InputStream secondPass = openResource(resourcePath)) {
            buildNetwork(secondPass, bbox, nodeCache, network);
        } catch (IOException e) {
            throw new RuntimeException("读取 PBF 第二遍失败: " + resourcePath, e);
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

    private static void collectNodes(InputStream inputStream,
                                     BBox bbox,
                                     Map<Long, NodeCoord> nodeCache) {
        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override
            public void initialize(Map<String, Object> metaData) {
            }

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

            @Override
            public void complete() {
            }

            @Override
            public void close() {
            }
        });
        reader.run();
    }

    private static void buildNetwork(InputStream inputStream,
                                     BBox bbox,
                                     Map<Long, NodeCoord> nodeCache,
                                     RoadNetwork network) {
        AtomicLong edgeIdSeed = new AtomicLong(1L);
        Map<Long, Integer> degreeCounter = new HashMap<>();

        PbfReader reader = new PbfReader(() -> inputStream, 1);
        reader.setSink(new Sink() {
            @Override
            public void initialize(Map<String, Object> metaData) {
            }

            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way)) {
                    return;
                }

                Map<String, String> tags = readTags(way.getTags());
                String highway = tags.get("highway");
                if (highway == null || !isVehicleHighway(highway, tags)) {
                    return;
                }

                boolean oneWay = isOneWay(tags);
                int maxSpeed = parseMaxSpeed(tags.get("maxspeed"));
                String name = tags.getOrDefault("name", "未命名道路");

                List<WayNode> wayNodes = way.getWayNodes();
                for (int i = 0; i < wayNodes.size() - 1; i++) {
                    long startNodeId = wayNodes.get(i).getNodeId();
                    long endNodeId = wayNodes.get(i + 1).getNodeId();
                    NodeCoord start = nodeCache.get(startNodeId);
                    NodeCoord end = nodeCache.get(endNodeId);
                    if (start == null || end == null) {
                        continue;
                    }

                    if (!bbox.segmentMayIntersect(start.lat, start.lon, end.lat, end.lon)) {
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
                    edge.setType(highway);
                    edge.setOneWay(oneWay);
                    edge.setMaxSpeed(maxSpeed);
                    edge.setLayerLevel(parseLayer(tags));
                    edge.setBridge(isBridge(tags));
                    edge.setTunnel(isTunnel(tags));
                    edge.setRampLike(isRampLike(highway, tags));
                    edge.setShapePoints(Arrays.asList(
                            new GeoPoint(start.lat, start.lon),
                            new GeoPoint(end.lat, end.lon)
                    ));
                    edge.refreshGeometry();
                    network.addEdge(edge);

                    degreeCounter.merge(startNodeId, 1, Integer::sum);
                    degreeCounter.merge(endNodeId, 1, Integer::sum);
                }
            }

            @Override
            public void complete() {
            }

            @Override
            public void close() {
            }
        });
        reader.run();

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
        if (EXCLUDED_HIGHWAY_TYPES.contains(highway)) {
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
