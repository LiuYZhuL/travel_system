package com.travel.travel_system.dto;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.*;

public class RoadNetwork implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final int NODE_PATH_CACHE_SIZE = 12000;
    private static final int ROUTE_DISTANCE_CACHE_SIZE = 20000;
    private static final int NEARBY_EDGE_CACHE_SIZE = 4000;
    private static final double NEARBY_CACHE_GRID_DEG = 0.0001;

    private Map<Long, RoadNode> nodes;
    private Map<Long, RoadEdge> edges;
    private List<RoadEdge> edgeList;
    private Map<Long, List<RoadEdge>> nodeAdjacencyList;

    private transient Map<String, List<Long>> nodePathCache;
    private transient Map<String, Double> routeDistanceCache;
    private transient Map<String, List<RoadEdge>> nearbyEdgeCache;

    public RoadNetwork() {
        this.nodes = new HashMap<>();
        this.edges = new HashMap<>();
        this.edgeList = new ArrayList<>();
        this.nodeAdjacencyList = new HashMap<>();
        initRuntimeCaches();
    }

    private void initRuntimeCaches() {
        this.nodePathCache = createLruMap(NODE_PATH_CACHE_SIZE);
        this.routeDistanceCache = createLruMap(ROUTE_DISTANCE_CACHE_SIZE);
        this.nearbyEdgeCache = createLruMap(NEARBY_EDGE_CACHE_SIZE);
    }

    private void ensureRuntimeCaches() {
        if (nodePathCache == null || routeDistanceCache == null || nearbyEdgeCache == null) {
            initRuntimeCaches();
        }
    }

    private <K, V> Map<K, V> createLruMap(final int maxSize) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        });
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initRuntimeCaches();
    }

    public void addNode(RoadNode node) {
        nodes.put(node.getId(), node);
    }

    public void addEdge(RoadEdge edge) {
        edge.refreshGeometry();
        edges.put(edge.getId(), edge);
        edgeList.add(edge);
        nodeAdjacencyList.computeIfAbsent(edge.getStartNodeId(), k -> new ArrayList<>()).add(edge);
        if (!edge.isOneWay()) {
            nodeAdjacencyList.computeIfAbsent(edge.getEndNodeId(), k -> new ArrayList<>()).add(createReverseEdge(edge));
        } else {
            nodeAdjacencyList.computeIfAbsent(edge.getEndNodeId(), k -> new ArrayList<>());
        }
        clearRuntimeCaches();
    }

    private void clearRuntimeCaches() {
        ensureRuntimeCaches();
        nodePathCache.clear();
        routeDistanceCache.clear();
        nearbyEdgeCache.clear();
    }

    private RoadEdge createReverseEdge(RoadEdge edge) {
        RoadEdge reverse = new RoadEdge(
                edge.getId() + 1_000_000,
                edge.getEndLat(), edge.getEndLon(),
                edge.getStartLat(), edge.getStartLon()
        );
        reverse.setStartNodeId(edge.getEndNodeId());
        reverse.setEndNodeId(edge.getStartNodeId());
        reverse.setName(edge.getName());
        reverse.setType(edge.getType());
        reverse.setOneWay(false);
        reverse.setMaxSpeed(edge.getMaxSpeed());
        reverse.setSourceWayId(edge.getSourceWayId());
        reverse.setSegmentIndex(edge.getSegmentIndex());
        reverse.setLayerLevel(edge.getLayerLevel());
        reverse.setBridge(edge.isBridge());
        reverse.setTunnel(edge.isTunnel());
        reverse.setRampLike(edge.isRampLike());
        List<GeoPoint> reversedShape = new ArrayList<>(edge.getShapePoints());
        Collections.reverse(reversedShape);
        reverse.setShapePoints(reversedShape);
        reverse.setConnectedEdgeIds(edge.getConnectedEdgeIds());
        reverse.refreshGeometry();
        return reverse;
    }

    public List<RoadEdge> findPath(RoadEdge fromEdge, RoadEdge toEdge) {
        if (fromEdge == null || toEdge == null) {
            return new ArrayList<>();
        }
        if (fromEdge.equals(toEdge)) {
            return new ArrayList<>(Collections.singletonList(fromEdge));
        }

        List<Long> startCandidates = Arrays.asList(fromEdge.getStartNodeId(), fromEdge.getEndNodeId());
        List<Long> endCandidates = Arrays.asList(toEdge.getStartNodeId(), toEdge.getEndNodeId());
        List<Long> bestPath = Collections.emptyList();
        double bestDistance = Double.MAX_VALUE;

        for (Long startNode : startCandidates) {
            for (Long endNode : endCandidates) {
                if (Objects.equals(startNode, endNode)) {
                    continue;
                }
                List<Long> nodePath = findNodePath(startNode, endNode);
                if (!nodePath.isEmpty()) {
                    double pathDistance = calculatePathDistance(nodePath);
                    if (pathDistance < bestDistance) {
                        bestDistance = pathDistance;
                        bestPath = nodePath;
                    }
                }
            }
        }

        if (bestPath.isEmpty()) {
            return new ArrayList<>();
        }

        List<RoadEdge> edgePath = new ArrayList<>();
        for (int i = 0; i < bestPath.size() - 1; i++) {
            RoadEdge connecting = findConnectingEdge(bestPath.get(i), bestPath.get(i + 1));
            if (connecting != null) {
                edgePath.add(connecting);
            }
        }
        return edgePath;
    }

    private double calculatePathDistance(List<Long> nodePath) {
        double total = 0.0;
        for (int i = 0; i < nodePath.size() - 1; i++) {
            RoadEdge edge = findConnectingEdge(nodePath.get(i), nodePath.get(i + 1));
            if (edge != null) {
                total += edge.getLengthM();
            }
        }
        return total;
    }

    private RoadEdge findConnectingEdge(Long fromNodeId, Long toNodeId) {
        List<RoadEdge> edgesFromNode = nodeAdjacencyList.get(fromNodeId);
        if (edgesFromNode == null) {
            return null;
        }
        for (RoadEdge edge : edgesFromNode) {
            if (edge.getEndNodeId() == toNodeId.longValue()) {
                return edge;
            }
        }
        return null;
    }

    public List<Long> findNodePath(Long startNodeId, Long endNodeId) {
        ensureRuntimeCaches();
        if (startNodeId == null || endNodeId == null) {
            return new ArrayList<>();
        }
        if (startNodeId.equals(endNodeId)) {
            return Collections.singletonList(startNodeId);
        }

        String cacheKey = buildNodePairKey(startNodeId, endNodeId);
        List<Long> cached = nodePathCache.get(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        Map<Long, Double> distances = new HashMap<>();
        Map<Long, Long> previousNodes = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        PriorityQueue<NodeDistance> pq = new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::getDistance));
        distances.put(startNodeId, 0.0);
        pq.offer(new NodeDistance(startNodeId, 0.0));

        while (!pq.isEmpty()) {
            NodeDistance current = pq.poll();
            long currentNode = current.nodeId;
            if (!visited.add(currentNode)) {
                continue;
            }
            if (currentNode == endNodeId) {
                break;
            }
            List<RoadEdge> adjacent = nodeAdjacencyList.get(currentNode);
            if (adjacent == null) {
                continue;
            }
            for (RoadEdge edge : adjacent) {
                long neighbor = edge.getEndNodeId();
                if (visited.contains(neighbor)) {
                    continue;
                }
                double nd = current.distance + edge.getLengthM();
                if (nd < distances.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distances.put(neighbor, nd);
                    previousNodes.put(neighbor, currentNode);
                    pq.offer(new NodeDistance(neighbor, nd));
                }
            }
        }

        List<Long> path = reconstructNodePath(startNodeId, endNodeId, previousNodes);
        if (!path.isEmpty()) {
            nodePathCache.put(cacheKey, new ArrayList<>(path));
        }
        return path;
    }

    private List<Long> reconstructNodePath(Long startNodeId, Long endNodeId, Map<Long, Long> previousNodes) {
        LinkedList<Long> path = new LinkedList<>();
        Long current = endNodeId;
        while (current != null) {
            path.addFirst(current);
            if (current.equals(startNodeId)) {
                return path;
            }
            current = previousNodes.get(current);
        }
        return new ArrayList<>();
    }

    public List<GeoPoint> getPathPoints(List<RoadEdge> edgePath) {
        List<GeoPoint> points = new ArrayList<>();
        if (edgePath == null || edgePath.isEmpty()) {
            return points;
        }
        for (int i = 0; i < edgePath.size(); i++) {
            List<GeoPoint> shape = edgePath.get(i).getShapePoints();
            if (shape.isEmpty()) {
                continue;
            }
            int from = i == 0 ? 0 : 1;
            for (int j = from; j < shape.size(); j++) {
                points.add(shape.get(j));
            }
        }
        return points;
    }

    public List<GeoPoint> interpolateAlongEdge(RoadEdge edge, double startLat, double startLon, double endLat, double endLon, int steps) {
        if (edge == null) {
            List<GeoPoint> direct = new ArrayList<>();
            for (int i = 0; i <= steps; i++) {
                double t = steps <= 0 ? 0.0 : (double) i / steps;
                direct.add(new GeoPoint(startLat + (endLat - startLat) * t, startLon + (endLon - startLon) * t));
            }
            return direct;
        }
        RoadEdge.Projection start = edge.project(startLat, startLon);
        RoadEdge.Projection end = edge.project(endLat, endLon);
        return edge.sliceByOffset(start.getOffsetMeters(), end.getOffsetMeters());
    }

    public RoadNode getNode(long nodeId) { return nodes.get(nodeId); }
    public RoadEdge getEdge(long edgeId) { return edges.get(edgeId); }
    public int getNodeCount() { return nodes.size(); }
    public int getEdgeCount() { return edges.size(); }
    public List<RoadEdge> getEdges() { return edgeList; }
    public Map<Long, RoadEdge> getEdgesMap() { return edges; }

    public void rebuildAdjacencyList() {
        nodeAdjacencyList.clear();
        for (RoadEdge edge : edgeList) {
            edge.refreshGeometry();
            nodeAdjacencyList.computeIfAbsent(edge.getStartNodeId(), k -> new ArrayList<>()).add(edge);
            if (!edge.isOneWay()) {
                nodeAdjacencyList.computeIfAbsent(edge.getEndNodeId(), k -> new ArrayList<>()).add(createReverseEdge(edge));
            } else {
                nodeAdjacencyList.computeIfAbsent(edge.getEndNodeId(), k -> new ArrayList<>());
            }
        }
        clearRuntimeCaches();
        System.out.println("邻接表重建完成: " + nodeAdjacencyList.size() + " 个节点有邻接边");
    }

    public List<RoadEdge> findNearbyEdges(double lat, double lon, double radius) {
        ensureRuntimeCaches();
        String cacheKey = buildNearbyEdgeCacheKey(lat, lon, radius);
        List<RoadEdge> cached = nearbyEdgeCache.get(cacheKey);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        double latRadiusDeg = radius / 111000.0;
        double lonRadiusDeg = radius / Math.max(1.0, 111000.0 * Math.cos(Math.toRadians(lat)));
        List<RoadEdge> nearby = new ArrayList<>();

        for (RoadEdge edge : edgeList) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (GeoPoint p : edge.getShapePoints()) {
                minLat = Math.min(minLat, p.lat);
                maxLat = Math.max(maxLat, p.lat);
                minLon = Math.min(minLon, p.lon);
                maxLon = Math.max(maxLon, p.lon);
            }
            minLat -= latRadiusDeg;
            maxLat += latRadiusDeg;
            minLon -= lonRadiusDeg;
            maxLon += lonRadiusDeg;
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
                continue;
            }
            double distance = edge.distanceToPolyline(lat, lon);
            if (distance <= radius) {
                nearby.add(edge);
            }
        }

        nearbyEdgeCache.put(cacheKey, new ArrayList<>(nearby));
        logNearbyEdgeDiagnostics(lat, lon, radius, nearby);
        return nearby;
    }

    private void logNearbyEdgeDiagnostics(double lat, double lon, double radius, List<RoadEdge> nearby) {
        double bestNearby = Double.POSITIVE_INFINITY;
        for (RoadEdge edge : nearby) {
            bestNearby = Math.min(bestNearby, edge.distanceToPolyline(lat, lon));
        }

        boolean suspicious = nearby.isEmpty() || bestNearby > 20.0;
        if (!suspicious || (radius < 80.0 && !nearby.isEmpty())) {
            return;
        }

        List<Map.Entry<RoadEdge, Double>> nearest = new ArrayList<>();
        for (RoadEdge edge : edgeList) {
            nearest.add(new AbstractMap.SimpleEntry<>(edge, edge.distanceToPolyline(lat, lon)));
        }
        nearest.sort(Comparator.comparingDouble(Map.Entry::getValue));

        System.out.println(String.format(
                Locale.ROOT,
                "[ROAD_NETWORK_DIAG] query=(%.6f,%.6f) radius=%.1f nearbyCount=%d bestNearby=%.2f totalEdges=%d",
                lat,
                lon,
                radius,
                nearby.size(),
                bestNearby,
                edgeList.size()
        ));

        int limit = Math.min(8, nearest.size());
        for (int i = 0; i < limit; i++) {
            RoadEdge edge = nearest.get(i).getKey();
            double distance = nearest.get(i).getValue();
            System.out.println(String.format(
                    Locale.ROOT,
                    "[ROAD_NETWORK_DIAG] rank=%d dist=%.2f road=%s|%s|layer=%d|bridge=%s|tunnel=%s|ramp=%s|len=%.1f|way=%s|seg=%s|shapePts=%d start=(%.6f,%.6f) end=(%.6f,%.6f)",
                    i,
                    distance,
                    safeRoadName(edge),
                    edge.getType(),
                    edge.getLayerLevel(),
                    edge.isBridge(),
                    edge.isTunnel(),
                    edge.isRampLike(),
                    edge.getLengthM(),
                    String.valueOf(edge.getSourceWayId()),
                    String.valueOf(edge.getSegmentIndex()),
                    edge.getShapePoints().size(),
                    edge.getStartLat(),
                    edge.getStartLon(),
                    edge.getEndLat(),
                    edge.getEndLon()
            ));
        }
    }

    private String safeRoadName(RoadEdge edge) {
        if (edge == null || edge.getName() == null || edge.getName().trim().isEmpty()) {
            return "<unnamed>";
        }
        return edge.getName().trim();
    }

    public boolean isInRoadNetwork(double lat, double lon, double radius) {
        return !findNearbyEdges(lat, lon, radius).isEmpty();
    }

    public double calculateRouteDistance(RoadEdge fromRoad, RoadEdge toRoad) {
        ensureRuntimeCaches();
        if (fromRoad == null || toRoad == null) {
            return 1_000_000.0;
        }
        if (fromRoad.equals(toRoad)) {
            return 0.0;
        }

        String cacheKey = buildEdgePairKey(fromRoad.getId(), toRoad.getId());
        Double cached = routeDistanceCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<RoadEdge> path = findPath(fromRoad, toRoad);
        double result;
        if (path.isEmpty()) {
            double fromLat = (fromRoad.getStartLat() + fromRoad.getEndLat()) / 2.0;
            double fromLon = (fromRoad.getStartLon() + fromRoad.getEndLon()) / 2.0;
            double toLat = (toRoad.getStartLat() + toRoad.getEndLat()) / 2.0;
            double toLon = (toRoad.getStartLon() + toRoad.getEndLon()) / 2.0;
            double direct = calculateGreatCircleDistance(fromLat, fromLon, toLat, toLon);
            result = Math.max(direct * 10.0, 100000.0);
        } else {
            double total = 0.0;
            for (RoadEdge edge : path) {
                total += edge.getLengthM();
            }
            result = total;
        }
        routeDistanceCache.put(cacheKey, result);
        return result;
    }

    private String buildNodePairKey(long startNodeId, long endNodeId) { return startNodeId + "->" + endNodeId; }
    private String buildEdgePairKey(long fromEdgeId, long toEdgeId) { return fromEdgeId + "->" + toEdgeId; }
    private String buildNearbyEdgeCacheKey(double lat, double lon, double radius) {
        long qLat = Math.round(lat / NEARBY_CACHE_GRID_DEG);
        long qLon = Math.round(lon / NEARBY_CACHE_GRID_DEG);
        long qRadius = Math.round(radius);
        return qLat + "_" + qLon + "_" + qRadius;
    }

    private double calculateGreatCircleDistance(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);
        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double aa = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa));
        return 6371000 * c;
    }


    private static class NodeDistance {
        private final long nodeId;
        private final double distance;
        private NodeDistance(long nodeId, double distance) { this.nodeId = nodeId; this.distance = distance; }
        public long getNodeId() { return nodeId; }
        public double getDistance() { return distance; }
    }
}
