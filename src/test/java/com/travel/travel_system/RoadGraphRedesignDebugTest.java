package com.travel.travel_system;

import com.travel.travel_system.algorithm.OsmRoadGraphLoader;
import com.travel.travel_system.algorithm.RoadGraphService;
import com.travel.travel_system.algorithm.model.GeoCoord;
import com.travel.travel_system.algorithm.model.RoadGraph;
import com.travel.travel_system.algorithm.model.RoadLoadRequest;
import com.travel.travel_system.algorithm.model.RoadSegment;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 新路网重构版的读图/累计/边界诊断测试。
 *
 * 用法：
 * 1) 先把 PBF 放在 resources 下，例如 static/pbf/henan-260321.osm.pbf
 * 2) 把这个测试类放到 test 目录
 * 3) 运行单元测试，观察控制台输出
 * 4) 如果不想走 JUnit，也可以直接运行 main()
 */
public class RoadGraphRedesignDebugTest {

    private static final String RESOURCE_PATH = "static/pbf/henan-260321.osm.pbf";

    private static final GeoCoord QUERY_POINT = new GeoCoord(34.15424894414818, 113.8076832030984);

    private static final List<GeoCoord> SAMPLE_CORRIDOR = Arrays.asList(
            new GeoCoord(34.15424894414818, 113.8076832030984),
            new GeoCoord(34.15069900000000, 113.81457600000000),
            new GeoCoord(34.15668500000000, 113.81327900000000)
    );

    public static void main(String[] args) {
        RoadGraphRedesignDebugTest test = new RoadGraphRedesignDebugTest();
        test.shouldLoadGraphByCorridorAndPrintSummary();
        test.shouldInspectNearbySegmentsAtQueryPoint();
        test.shouldLoadByWindowServiceAndPrintAccumulation();
        test.shouldCheckBoundaryPathAvailability();
    }

    @Test
    public void shouldLoadGraphByCorridorAndPrintSummary() {
        RoadLoadRequest request = buildDefaultRequest(SAMPLE_CORRIDOR);

        OsmRoadGraphLoader loader = new OsmRoadGraphLoader();
        RoadGraph graph = loader.load(request);

        System.out.println("====================================================");
        System.out.println("[ROAD_GRAPH_DEBUG] shouldLoadGraphByCorridorAndPrintSummary");
        System.out.println("[ROAD_GRAPH_DEBUG] resource=" + RESOURCE_PATH);
        System.out.println("[ROAD_GRAPH_DEBUG] junctions=" + graph.getMetadata().getJunctionCount()
                + " segments=" + graph.getMetadata().getSegmentCount()
                + " restrictions=" + graph.getMetadata().getRestrictionCount()
                + " weakComponents=" + graph.getMetadata().getWeakComponents()
                + " largestWeakRatio=" + graph.getMetadata().getLargestWeakComponentRatio()
                + " totalLengthM=" + graph.getMetadata().getTotalLengthMeters());
        System.out.println("====================================================");
    }

    @Test
    public void shouldInspectNearbySegmentsAtQueryPoint() {
        RoadLoadRequest request = buildDefaultRequest(SAMPLE_CORRIDOR);
        OsmRoadGraphLoader loader = new OsmRoadGraphLoader();
        RoadGraph graph = loader.load(request);

        List<RoadSegmentDistance> nearest = graph.nearbySegments(
                        QUERY_POINT.getLat(),
                        QUERY_POINT.getLon(),
                        600.0
                ).stream()
                .map(segment -> new RoadSegmentDistance(segment, segment.distanceTo(QUERY_POINT.getLat(), QUERY_POINT.getLon())))
                .sorted(Comparator.comparingDouble(RoadSegmentDistance::distanceMeters))
                .limit(12)
                .collect(Collectors.toList());

        System.out.println("====================================================");
        System.out.println("[ROAD_GRAPH_DEBUG] shouldInspectNearbySegmentsAtQueryPoint");
        System.out.println("[ROAD_GRAPH_DEBUG] query=(" + QUERY_POINT.getLat() + "," + QUERY_POINT.getLon() + ")");
        System.out.println("[ROAD_GRAPH_DEBUG] nearbyWithin600m=" + nearest.size());
        for (int i = 0; i < nearest.size(); i++) {
            RoadSegmentDistance item = nearest.get(i);
            RoadSegment s = item.segment();
            System.out.println(String.format(
                    Locale.ROOT,
                    "[ROAD_GRAPH_DEBUG] rank=%d dist=%.2f osmWay=%d seg=%d type=%s layer=%d bridge=%s tunnel=%s ramp=%s oneWay=%s startNode=%d endNode=%d name=%s",
                    i,
                    item.distanceMeters(),
                    s.getOsmWayId(),
                    s.getSegmentOrdinal(),
                    s.getHighwayType(),
                    s.getLayer(),
                    s.isBridge(),
                    s.isTunnel(),
                    s.isRamp(),
                    s.isOneWay(),
                    s.getStartNodeId(),
                    s.getEndNodeId(),
                    s.getName()
            ));
        }
        System.out.println("====================================================");
    }

    @Test
    public void shouldLoadByWindowServiceAndPrintAccumulation() {
        RoadGraphService service = new RoadGraphService();
        RoadGraphService.LoadContext context = service.prepare(SAMPLE_CORRIDOR, RESOURCE_PATH);

        System.out.println("====================================================");
        System.out.println("[ROAD_GRAPH_DEBUG] shouldLoadByWindowServiceAndPrintAccumulation");
        System.out.println("[ROAD_GRAPH_DEBUG] windows=" + context.getWindows().size());

        for (int i = 0; i < context.getWindows().size(); i++) {
            RoadGraphService.Window window = context.getWindows().get(i);
            RoadGraph merged = service.loadWindowGraph(context, i);
            System.out.println(String.format(
                    Locale.ROOT,
                    "[ROAD_GRAPH_DEBUG] window=%d pointRange=[%d,%d] loadedTiles=%d mergedJunctions=%d mergedSegments=%d heading=%s",
                    i,
                    window.getStartIndex(),
                    window.getEndIndex(),
                    context.getLoadedTileKeys().size(),
                    merged.getMetadata().getJunctionCount(),
                    merged.getMetadata().getSegmentCount(),
                    String.valueOf(window.getHeadingDegrees())
            ));
        }
        System.out.println("====================================================");
    }

    @Test
    public void shouldCheckBoundaryPathAvailability() {
        RoadGraphService service = new RoadGraphService();
        RoadGraphService.LoadContext context = service.prepare(SAMPLE_CORRIDOR, RESOURCE_PATH);

        if (context.getWindows().isEmpty()) {
            System.out.println("[ROAD_GRAPH_DEBUG] no windows");
            return;
        }

        RoadGraph graph = service.loadWindowGraph(context, 0);
        RoadSegment startSegment = findNearestSegment(graph, SAMPLE_CORRIDOR.get(0));
        RoadSegment endSegment = findNearestSegment(graph, SAMPLE_CORRIDOR.get(SAMPLE_CORRIDOR.size() - 1));

        if (startSegment == null || endSegment == null) {
            System.out.println("[ROAD_GRAPH_DEBUG] boundary path check skipped because nearest segment not found");
            return;
        }

        PathResult firstTry = shortestPath(graph, startSegment.getEndNodeId(), endSegment.getStartNodeId());
        if (firstTry.found()) {
            System.out.println("====================================================");
            System.out.println("[ROAD_GRAPH_DEBUG] shouldCheckBoundaryPathAvailability");
            System.out.println("[ROAD_GRAPH_DEBUG] firstTryPathFound=true distance=" + firstTry.distanceMeters()
                    + " nodeCount=" + firstTry.nodePath().size());
            System.out.println("====================================================");
            return;
        }

        RoadGraph expanded = service.expandForward(context, 0, context.getWindows().get(0).getHeadingDegrees());
        PathResult secondTry = shortestPath(expanded, startSegment.getEndNodeId(), endSegment.getStartNodeId());

        System.out.println("====================================================");
        System.out.println("[ROAD_GRAPH_DEBUG] shouldCheckBoundaryPathAvailability");
        System.out.println("[ROAD_GRAPH_DEBUG] firstTryPathFound=" + firstTry.found());
        System.out.println("[ROAD_GRAPH_DEBUG] secondTryPathFound=" + secondTry.found());
        System.out.println("[ROAD_GRAPH_DEBUG] secondTryDistance=" + secondTry.distanceMeters());
        System.out.println("[ROAD_GRAPH_DEBUG] secondTryNodeCount=" + secondTry.nodePath().size());
        System.out.println("====================================================");
    }

    private RoadLoadRequest buildDefaultRequest(List<GeoCoord> corridor) {
        RoadLoadRequest request = new RoadLoadRequest();
        request.setResourcePath(RESOURCE_PATH);
        request.setPolylineWgs84(corridor);
        request.setCoreBufferMeters(180.0);
        request.setHaloBufferMeters(450.0);
        request.setLoadRestrictions(true);
        request.setIncludeConstruction(true);
        request.setIncludeDestinationAccess(true);
        request.setIncludePrivateAccess(false);
        return request;
    }

    private RoadSegment findNearestSegment(RoadGraph graph, GeoCoord point) {
        List<RoadSegment> candidates = graph.nearbySegments(point.getLat(), point.getLon(), 800.0);
        RoadSegment best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (RoadSegment segment : candidates) {
            double distance = segment.distanceTo(point.getLat(), point.getLon());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = segment;
            }
        }
        return best;
    }

    private PathResult shortestPath(RoadGraph graph, long startNodeId, long endNodeId) {
        if (graph == null || graph.getJunctions().isEmpty()) {
            return PathResult.notFound();
        }
        if (startNodeId == endNodeId) {
            return new PathResult(true, 0.0, Collections.singletonList(startNodeId));
        }

        Map<Long, Double> dist = new HashMap<>();
        Map<Long, Long> prev = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::distanceMeters));

        dist.put(startNodeId, 0.0);
        pq.offer(new NodeDistance(startNodeId, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            if (!visited.add(current.nodeId())) {
                continue;
            }
            if (current.nodeId() == endNodeId) {
                break;
            }

            for (RoadSegment outgoing : graph.outgoing(current.nodeId())) {
                long next = outgoing.getEndNodeId();
                if (visited.contains(next)) {
                    continue;
                }
                double nextDist = current.distanceMeters() + outgoing.getLengthMeters();
                if (nextDist < dist.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    dist.put(next, nextDist);
                    prev.put(next, current.nodeId());
                    pq.offer(new NodeDistance(next, nextDist));
                }
            }
        }

        if (!dist.containsKey(endNodeId)) {
            return PathResult.notFound();
        }

        LinkedList<Long> path = new LinkedList<>();
        Long cursor = endNodeId;
        while (cursor != null) {
            path.addFirst(cursor);
            if (cursor == startNodeId) {
                break;
            }
            cursor = prev.get(cursor);
        }
        return new PathResult(true, dist.get(endNodeId), path);
    }

    private record RoadSegmentDistance(RoadSegment segment, double distanceMeters) {}
    private record NodeDistance(long nodeId, double distanceMeters) {}

    private static class PathResult {
        private final boolean found;
        private final double distanceMeters;
        private final List<Long> nodePath;

        private PathResult(boolean found, double distanceMeters, List<Long> nodePath) {
            this.found = found;
            this.distanceMeters = distanceMeters;
            this.nodePath = nodePath == null ? new ArrayList<>() : nodePath;
        }

        static PathResult notFound() {
            return new PathResult(false, Double.POSITIVE_INFINITY, new ArrayList<>());
        }

        boolean found() {
            return found;
        }

        double distanceMeters() {
            return distanceMeters;
        }

        List<Long> nodePath() {
            return nodePath;
        }
    }
}
