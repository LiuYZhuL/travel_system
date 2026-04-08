package com.travel.travel_system.algorithm;

import com.travel.travel_system.algorithm.model.*;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.*;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.pbf2.v0_6.PbfReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * 从头设计的离线 OSM 路网加载器。
 *
 * 设计原则：
 * 1) 只负责“读取与构图”，不混入上层匹配逻辑；
 * 2) 输入统一是轨迹走廊请求 RoadLoadRequest；
 * 3) 构图时不在边界硬裁断 segment；
 * 4) restriction 关系按 way 链原样读入；
 * 5) 所有派生结构（邻接、空间索引、弱连通统计）交给 RoadGraph 自己构建。
 */
public class OsmRoadGraphLoader {

    private static final Logger log = LoggerFactory.getLogger(OsmRoadGraphLoader.class);
    private static final long SEGMENT_ID_WAY_MULTIPLIER = 1_000_000L;

    public RoadGraph load(RoadLoadRequest request) {
        if (request == null || request.getResourcePath() == null || request.getPolylineWgs84() == null || request.getPolylineWgs84().isEmpty()) {
            throw new IllegalArgumentException("resourcePath/polylineWgs84 不能为空");
        }

        BBox haloBBox = BBox.fromPolyline(request.getPolylineWgs84(), request.getHaloBufferMeters());
        log.warn("[OSM_LOAD_START] resourcePath={} polylinePoints={} coreBufferMeters={} haloBufferMeters={} bbox=({}, {}, {}, {})",
                request.getResourcePath(),
                request.getPolylineWgs84() == null ? 0 : request.getPolylineWgs84().size(),
                request.getCoreBufferMeters(),
                request.getHaloBufferMeters(),
                round6(haloBBox.minLat), round6(haloBBox.maxLat), round6(haloBBox.minLon), round6(haloBBox.maxLon));

        Map<Long, NodeCoord> seedNodes = new HashMap<>(16_384);
        Set<Long> relevantNodeIds = new LinkedHashSet<>(65_536);
        Set<Long> relevantWayIds = new LinkedHashSet<>(16_384);
        Map<Long, NodeCoord> requiredNodes = new HashMap<>(65_536);

        try (InputStream first = open(request.getResourcePath())) {
            collectSeedNodes(first, haloBBox, seedNodes);
            log.warn("[OSM_LOAD_PASS1] seedNodes={}", seedNodes.size());
        } catch (IOException e) {
            throw new RuntimeException("PBF 第一遍读取失败", e);
        }

        try (InputStream second = open(request.getResourcePath())) {
            collectRelevantWays(second, request, seedNodes.keySet(), relevantNodeIds, relevantWayIds);
            log.warn("[OSM_LOAD_PASS2] relevantWays={} relevantNodes={}", relevantWayIds.size(), relevantNodeIds.size());
        } catch (IOException e) {
            throw new RuntimeException("PBF 第二遍读取失败", e);
        }

        try (InputStream third = open(request.getResourcePath())) {
            collectRequiredNodes(third, relevantNodeIds, requiredNodes);
            log.warn("[OSM_LOAD_PASS3] requiredNodes={}", requiredNodes.size());
        } catch (IOException e) {
            throw new RuntimeException("PBF 第三遍读取失败", e);
        }

        RoadGraph graph = new RoadGraph();
        try (InputStream fourth = open(request.getResourcePath())) {
            buildGraph(fourth, request, relevantWayIds, requiredNodes, graph);
            log.warn("[OSM_LOAD_PASS4] segments={} junctions={}", graph.getSegments() == null ? 0 : graph.getSegments().size(), graph.getJunctions() == null ? 0 : graph.getJunctions().size());
        } catch (IOException e) {
            throw new RuntimeException("PBF 第四遍读取失败", e);
        }

        if (request.isLoadRestrictions()) {
            try (InputStream fifth = open(request.getResourcePath())) {
                collectRestrictions(fifth, relevantWayIds, graph);
                log.warn("[OSM_LOAD_PASS5] restrictions={}", graph.getRestrictions() == null ? 0 : graph.getRestrictions().size());
            } catch (IOException e) {
                throw new RuntimeException("PBF 第五遍读取失败", e);
            }
        }

        RoadGraphMetadata metadata = new RoadGraphMetadata();
        metadata.setSourceResourcePath(request.getResourcePath());
        metadata.setBuildMode("corridor-halo");
        metadata.setQueryMinLat(haloBBox.minLat);
        metadata.setQueryMaxLat(haloBBox.maxLat);
        metadata.setQueryMinLon(haloBBox.minLon);
        metadata.setQueryMaxLon(haloBBox.maxLon);
        metadata.setCoreBufferMeters(request.getCoreBufferMeters());
        metadata.setHaloBufferMeters(request.getHaloBufferMeters());
        graph.setMetadata(metadata);
        graph.buildDerivedStructures();
        log.warn("[OSM_LOAD_DONE] segmentIdMode=stable_way_segment_direction segments={} junctions={} restrictions={} highwaySummary={} sampleSegments={}",
                graph.getSegments() == null ? 0 : graph.getSegments().size(),
                graph.getJunctions() == null ? 0 : graph.getJunctions().size(),
                graph.getRestrictions() == null ? 0 : graph.getRestrictions().size(),
                summarizeHighways(graph),
                summarizeSegments(graph));
        return graph;
    }

    private void collectSeedNodes(InputStream in, BBox haloBBox, Map<Long, NodeCoord> seedNodes) {
        PbfReader reader = new PbfReader(() -> in, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer.getEntity() instanceof Node node) {
                    if (haloBBox.contains(node.getLatitude(), node.getLongitude())) {
                        seedNodes.put(node.getId(), new NodeCoord(node.getLatitude(), node.getLongitude()));
                    }
                }
            }
            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private void collectRelevantWays(InputStream in,
                                     RoadLoadRequest request,
                                     Set<Long> seedNodeIds,
                                     Set<Long> relevantNodeIds,
                                     Set<Long> relevantWayIds) {
        PbfReader reader = new PbfReader(() -> in, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way)) {
                    return;
                }
                Map<String, String> tags = tags(way.getTags());
                String highway = normalizeHighwayType(tags, request);
                if (!isVehicleWay(highway, tags, request)) {
                    return;
                }

                boolean hit = false;
                for (WayNode wayNode : way.getWayNodes()) {
                    if (seedNodeIds.contains(wayNode.getNodeId())) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return;
                }

                relevantWayIds.add(way.getId());
                for (WayNode wayNode : way.getWayNodes()) {
                    relevantNodeIds.add(wayNode.getNodeId());
                }
            }
            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private void collectRequiredNodes(InputStream in,
                                      Set<Long> relevantNodeIds,
                                      Map<Long, NodeCoord> requiredNodes) {
        PbfReader reader = new PbfReader(() -> in, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override
            public void process(EntityContainer entityContainer) {
                if (entityContainer.getEntity() instanceof Node node) {
                    if (relevantNodeIds.contains(node.getId())) {
                        requiredNodes.put(node.getId(), new NodeCoord(node.getLatitude(), node.getLongitude()));
                    }
                }
            }
            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private void buildGraph(InputStream in,
                            RoadLoadRequest request,
                            Set<Long> relevantWayIds,
                            Map<Long, NodeCoord> requiredNodes,
                            RoadGraph graph) {
        PbfReader reader = new PbfReader(() -> in, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Way way) || !relevantWayIds.contains(way.getId())) {
                    return;
                }

                Map<String, String> tags = tags(way.getTags());
                String highway = normalizeHighwayType(tags, request);
                if (!isVehicleWay(highway, tags, request)) {
                    return;
                }

                boolean oneWay = isOneWay(tags);
                boolean reverseOneWay = isReverseOneWay(tags);

                List<WayNode> refs = way.getWayNodes();
                for (int i = 0; i < refs.size() - 1; i++) {
                    long originalStart = refs.get(i).getNodeId();
                    long originalEnd = refs.get(i + 1).getNodeId();
                    NodeCoord start = requiredNodes.get(originalStart);
                    NodeCoord end = requiredNodes.get(originalEnd);
                    if (start == null || end == null) {
                        continue;
                    }

                    long actualStart = reverseOneWay ? originalEnd : originalStart;
                    long actualEnd = reverseOneWay ? originalStart : originalEnd;
                    NodeCoord actualStartCoord = reverseOneWay ? end : start;
                    NodeCoord actualEndCoord = reverseOneWay ? start : end;

                    graph.addJunction(new RoadJunction(actualStart, actualStartCoord.lat, actualStartCoord.lon));
                    graph.addJunction(new RoadJunction(actualEnd, actualEndCoord.lat, actualEndCoord.lon));

                    RoadSegment segment = new RoadSegment(buildStableSegmentId(way.getId(), i, false), way.getId(), i);
                    segment.setStartNodeId(actualStart);
                    segment.setEndNodeId(actualEnd);
                    segment.setName(resolveName(tags));
                    segment.setHighwayType(highway);
                    segment.setOneWay(oneWay);
                    segment.setSpeedKph(parseMaxSpeed(tags.get("maxspeed")));
                    segment.setLayer(parseLayer(tags));
                    segment.setBridge(isBridge(tags));
                    segment.setTunnel(isTunnel(tags));
                    segment.setRamp(isRamp(highway, tags));
                    segment.setConstruction(isConstruction(tags));
                    segment.setAccess(normalize(tags.get("access")));
                    segment.setMotorVehicle(normalize(tags.get("motor_vehicle")));
                    segment.setMotorcar(normalize(tags.get("motorcar")));
                    segment.setServiceType(normalize(tags.get("service")));
                    segment.setJunctionType(normalize(tags.get("junction")));

                    List<GeoCoord> geometry = new ArrayList<>(2);
                    geometry.add(new GeoCoord(actualStartCoord.lat, actualStartCoord.lon));
                    geometry.add(new GeoCoord(actualEndCoord.lat, actualEndCoord.lon));
                    segment.setGeometry(geometry);

                    graph.addSegment(segment);

                    if (!segment.isOneWay()) {
                        graph.addSegment(segment.copyAsReverse(buildStableSegmentId(way.getId(), i, true)));
                    }
                }
            }
            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }

    private void collectRestrictions(InputStream in,
                                     Set<Long> relevantWayIds,
                                     RoadGraph graph) {
        PbfReader reader = new PbfReader(() -> in, 1);
        reader.setSink(new Sink() {
            @Override public void initialize(Map<String, Object> metaData) {}
            @Override
            public void process(EntityContainer entityContainer) {
                if (!(entityContainer.getEntity() instanceof Relation relation)) {
                    return;
                }

                Map<String, String> tags = tags(relation.getTags());
                if (!"restriction".equalsIgnoreCase(tags.get("type"))) {
                    return;
                }

                Long fromWay = null;
                Long toWay = null;
                Long viaNode = null;
                List<Long> viaWays = new ArrayList<>();

                for (RelationMember member : relation.getMembers()) {
                    if ("from".equals(member.getMemberRole()) && member.getMemberType() == EntityType.Way) {
                        fromWay = member.getMemberId();
                    } else if ("to".equals(member.getMemberRole()) && member.getMemberType() == EntityType.Way) {
                        toWay = member.getMemberId();
                    } else if ("via".equals(member.getMemberRole()) && member.getMemberType() == EntityType.Node) {
                        viaNode = member.getMemberId();
                    } else if ("via".equals(member.getMemberRole()) && member.getMemberType() == EntityType.Way) {
                        viaWays.add(member.getMemberId());
                    }
                }

                if (fromWay == null || toWay == null) {
                    return;
                }
                if (!relevantWayIds.contains(fromWay) && !relevantWayIds.contains(toWay)) {
                    return;
                }

                RoadRestriction restriction = new RoadRestriction();
                restriction.setRelationId(relation.getId());
                restriction.setType(firstNonBlank(tags.get("restriction:motorcar"), tags.get("restriction:vehicle"), tags.get("restriction")));
                restriction.setFromWayId(fromWay);
                restriction.setToWayId(toWay);
                restriction.setViaNodeId(viaNode);
                restriction.setViaWayIds(viaWays);
                restriction.setExceptModes(tags.get("except"));
                graph.addRestriction(restriction);
            }
            @Override public void complete() {}
            @Override public void close() {}
        });
        reader.run();
    }


    private String summarizeHighways(RoadGraph graph) {
        if (graph == null || graph.getSegments() == null || graph.getSegments().isEmpty()) {
            return "{}";
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (RoadSegment segment : graph.getSegments().values()) {
            String key = segment.getHighwayType() == null ? "-" : segment.getHighwayType();
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts.toString();
    }

    private String summarizeSegments(RoadGraph graph) {
        if (graph == null || graph.getSegments() == null || graph.getSegments().isEmpty()) {
            return "[]";
        }
        List<String> items = new ArrayList<>();
        int count = 0;
        for (RoadSegment segment : graph.getSegments().values()) {
            items.add("seg=" + segment.getSegmentId() + "/way=" + segment.getOsmWayId() + "/name=" + (segment.getName() == null ? "-" : segment.getName()) + "/type=" + (segment.getHighwayType() == null ? "-" : segment.getHighwayType()));
            count++;
            if (count >= 8) {
                break;
            }
        }
        return items.toString();
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private InputStream open(String resourcePath) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("资源不存在: " + resourcePath);
        }
        return in;
    }

    private Map<String, String> tags(Collection<Tag> tags) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Tag tag : tags) {
            map.put(tag.getKey(), tag.getValue());
        }
        return map;
    }

    private String resolveName(Map<String, String> tags) {
        String name = firstNonBlank(tags.get("name"), tags.get("ref"));
        return name == null ? "未命名道路" : name;
    }

    private String normalizeHighwayType(Map<String, String> tags, RoadLoadRequest request) {
        String highway = normalize(tags.get("highway"));
        if (highway == null) {
            return null;
        }
        if (!"construction".equalsIgnoreCase(highway)) {
            return highway;
        }
        if (!request.isIncludeConstruction()) {
            return null;
        }
        String subtype = normalize(tags.get("construction"));
        return subtype != null && request.getAllowedHighways().contains(subtype) ? subtype : null;
    }

    private boolean isVehicleWay(String highway, Map<String, String> tags, RoadLoadRequest request) {
        if (highway == null || !request.getAllowedHighways().contains(highway)) {
            return false;
        }
        if (!request.isIncludePrivateAccess()) {
            String access = normalize(tags.get("access"));
            if ("private".equals(access) || "no".equals(access)) {
                return false;
            }
        }
        if (!request.isIncludeDestinationAccess()) {
            String access = normalize(tags.get("access"));
            if ("destination".equals(access)) {
                return false;
            }
        }
        String motorVehicle = normalize(tags.get("motor_vehicle"));
        String motorcar = normalize(tags.get("motorcar"));
        String vehicle = normalize(tags.get("vehicle"));
        return !"no".equals(motorVehicle) && !"no".equals(motorcar) && !"no".equals(vehicle);
    }

    private boolean isOneWay(Map<String, String> tags) {
        String oneWay = normalize(tags.get("oneway"));
        String junction = normalize(tags.get("junction"));
        return "yes".equals(oneWay) || "1".equals(oneWay) || "-1".equals(oneWay) || "roundabout".equals(junction);
    }

    private boolean isReverseOneWay(Map<String, String> tags) {
        return "-1".equals(normalize(tags.get("oneway")));
    }

    private boolean isBridge(Map<String, String> tags) {
        String value = normalize(tags.get("bridge"));
        return value != null && !"no".equals(value);
    }

    private boolean isTunnel(Map<String, String> tags) {
        String value = normalize(tags.get("tunnel"));
        return value != null && !"no".equals(value);
    }

    private boolean isConstruction(Map<String, String> tags) {
        return "construction".equals(normalize(tags.get("highway"))) || normalize(tags.get("construction")) != null;
    }

    private boolean isRamp(String highway, Map<String, String> tags) {
        String junction = normalize(tags.get("junction"));
        return (highway != null && highway.endsWith("_link")) || "slip_road".equals(junction) || "ramp".equals(junction);
    }

    private int parseLayer(Map<String, String> tags) {
        try {
            String value = normalize(tags.get("layer"));
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parseMaxSpeed(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 50;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 50;
        }
        int speed = Integer.parseInt(digits);
        return normalized.contains("mph") ? (int) Math.round(speed * 1.60934) : speed;
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private long buildStableSegmentId(long wayId, int segmentIndex, boolean reverseDirection) {
        if (segmentIndex < 0 || segmentIndex >= 499_999) {
            throw new IllegalArgumentException("segmentIndex 超出稳定 ID 编码范围: " + segmentIndex + ", wayId=" + wayId);
        }
        long localPart = segmentIndex * 2L + (reverseDirection ? 1L : 0L) + 1L;
        return wayId * SEGMENT_ID_WAY_MULTIPLIER + localPart;
    }

    private static class NodeCoord {
        final double lat;
        final double lon;

        private NodeCoord(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class BBox {
        final double minLat;
        final double maxLat;
        final double minLon;
        final double maxLon;

        private BBox(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }

        static BBox fromPolyline(List<GeoCoord> polyline, double bufferMeters) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (GeoCoord p : polyline) {
                minLat = Math.min(minLat, p.getLat());
                maxLat = Math.max(maxLat, p.getLat());
                minLon = Math.min(minLon, p.getLon());
                maxLon = Math.max(maxLon, p.getLon());
            }
            double centerLat = (minLat + maxLat) / 2.0;
            double latDelta = bufferMeters / 111_000.0;
            double lonDelta = bufferMeters / Math.max(1.0, 111_000.0 * Math.cos(Math.toRadians(centerLat)));
            return new BBox(minLat - latDelta, maxLat + latDelta, minLon - lonDelta, maxLon + lonDelta);
        }

        boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }
    }
}
