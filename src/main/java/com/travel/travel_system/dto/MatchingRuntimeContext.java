package com.travel.travel_system.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MatchingRuntimeContext {
    private int depth;
    private final Map<String, List<RoadEdge>> nearbyEdgeCache;
    private final Map<String, Double> routeDistanceCache;

    public MatchingRuntimeContext() {
        this.nearbyEdgeCache = newLruCache(TrackMatchingConstants.MAX_NEARBY_EDGE_CACHE_SIZE);
        this.routeDistanceCache = newLruCache(TrackMatchingConstants.MAX_ROUTE_DISTANCE_CACHE_SIZE);
    }

    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public Map<String, List<RoadEdge>> getNearbyEdgeCache() { return nearbyEdgeCache; }
    public Map<String, Double> getRouteDistanceCache() { return routeDistanceCache; }

    private static <K, V> Map<K, V> newLruCache(final int maxSize) {
        return new LinkedHashMap<K, V>(Math.min(maxSize, 128), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxSize;
            }
        };
    }
}
