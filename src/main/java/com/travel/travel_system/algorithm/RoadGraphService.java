package com.travel.travel_system.algorithm;

import com.travel.travel_system.algorithm.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 从头设计的服务层：
 * 1) 只面向“轨迹窗口 + corridor + tile”；
 * 2) 不再暴露 center/radius 作为主入口；
 * 3) 支持累计子图、边界扩张、tile 级缓存；
 * 4) 保证路径获取时始终在 halo 保护环内完成。
 */
public class RoadGraphService {

    private static final Logger log = LoggerFactory.getLogger(RoadGraphService.class);

    private static final double TILE_SIZE_METERS = 1200.0;
    private static final double TILE_OVERLAP_METERS = 250.0;
    private static final double BOUNDARY_EXPAND_METERS = 800.0;

    private final OsmRoadGraphLoader loader = new OsmRoadGraphLoader();

    private final Map<String, RoadGraph> tileCache = Collections.synchronizedMap(
            new LinkedHashMap<String, RoadGraph>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RoadGraph> eldest) {
                    return size() > 64;
                }
            }
    );

    public LoadContext prepare(List<GeoCoord> trajectoryWgs84, String resourcePath) {
        log.warn("[ROAD_GRAPH_PREPARE] pointCount={} resourcePath={}", trajectoryWgs84 == null ? 0 : trajectoryWgs84.size(), resourcePath);
        LoadContext context = new LoadContext();
        context.resourcePath = resourcePath;
        context.windows = buildWindows(trajectoryWgs84, 30, 8);
        context.loadedTileKeys = new LinkedHashSet<>();
        context.tileGraphs = new LinkedHashMap<>();
        context.mergedGraph = new RoadGraph();
        log.warn("[ROAD_GRAPH_PREPARE] windows={}", context.windows == null ? 0 : context.windows.size());
        return context;
    }

    public RoadGraph loadWindowGraph(LoadContext context, int windowIndex) {
        if (context == null || windowIndex < 0 || windowIndex >= context.windows.size()) {
            return new RoadGraph();
        }

        Window window = context.windows.get(windowIndex);
        List<TileKey> requiredTiles = resolveTiles(window.haloBox, context.resourcePath);
        int cacheHits = 0;
        int cacheMisses = 0;
        log.warn("[ROAD_GRAPH_WINDOW] window={} pointRange={}..{} requiredTiles={} headingDeg={}",
                window.getIndex(), window.getStartIndex(), window.getEndIndex(), requiredTiles.size(),
                window.getHeadingDegrees() == null ? "-" : String.format(Locale.ROOT, "%.1f", window.getHeadingDegrees()));
        for (TileKey key : requiredTiles) {
            if (context.loadedTileKeys.contains(key.cacheKey)) {
                cacheHits++;
                continue;
            }

            RoadGraph tileGraph = tileCache.get(key.cacheKey);
            if (tileGraph == null) {
                cacheMisses++;
                RoadLoadRequest request = new RoadLoadRequest();
                request.setResourcePath(key.resourcePath);
                request.setPolylineWgs84(key.toPolyline());
                request.setCoreBufferMeters(TILE_SIZE_METERS / 2.0);
                request.setHaloBufferMeters(TILE_OVERLAP_METERS);
                tileGraph = loader.load(request);
                tileCache.put(key.cacheKey, tileGraph);
                log.warn("[ROAD_GRAPH_TILE] cache=MISS key={} segments={} junctions={} restrictions={}",
                        key.cacheKey,
                        tileGraph.getSegments() == null ? 0 : tileGraph.getSegments().size(),
                        tileGraph.getJunctions() == null ? 0 : tileGraph.getJunctions().size(),
                        tileGraph.getRestrictions() == null ? 0 : tileGraph.getRestrictions().size());
            } else {
                cacheHits++;
                log.warn("[ROAD_GRAPH_TILE] cache=HIT key={} segments={} junctions={} restrictions={}",
                        key.cacheKey,
                        tileGraph.getSegments() == null ? 0 : tileGraph.getSegments().size(),
                        tileGraph.getJunctions() == null ? 0 : tileGraph.getJunctions().size(),
                        tileGraph.getRestrictions() == null ? 0 : tileGraph.getRestrictions().size());
            }

            context.tileGraphs.put(key.cacheKey, tileGraph);
            context.loadedTileKeys.add(key.cacheKey);
            context.mergedGraph = merge(context.mergedGraph, tileGraph);
        }
        log.warn("[ROAD_GRAPH_WINDOW] window={} loadedTileKeys={} cacheHits={} cacheMisses={} mergedSegments={} mergedJunctions={} mergedRestrictions={}",
                window.getIndex(),
                context.loadedTileKeys == null ? 0 : context.loadedTileKeys.size(),
                cacheHits,
                cacheMisses,
                context.mergedGraph.getSegments() == null ? 0 : context.mergedGraph.getSegments().size(),
                context.mergedGraph.getJunctions() == null ? 0 : context.mergedGraph.getJunctions().size(),
                context.mergedGraph.getRestrictions() == null ? 0 : context.mergedGraph.getRestrictions().size());
        return context.mergedGraph;
    }

    public RoadGraph expandForward(LoadContext context, int windowIndex, Double headingDegrees) {
        if (context == null || windowIndex < 0 || windowIndex >= context.windows.size()) {
            return context == null ? new RoadGraph() : context.mergedGraph;
        }
        Window window = context.windows.get(windowIndex);
        BBox expanded = window.haloBox.expandForward(headingDegrees, BOUNDARY_EXPAND_METERS);
        List<TileKey> requiredTiles = resolveTiles(expanded, context.resourcePath);
        int cacheHits = 0;
        int cacheMisses = 0;
        log.warn("[ROAD_GRAPH_EXPAND] window={} headingDeg={} requiredTiles={}", window.getIndex(), headingDegrees, requiredTiles.size());
        for (TileKey key : requiredTiles) {
            if (context.loadedTileKeys.contains(key.cacheKey)) {
                cacheHits++;
                continue;
            }
            RoadGraph tileGraph = tileCache.get(key.cacheKey);
            if (tileGraph == null) {
                cacheMisses++;
                RoadLoadRequest request = new RoadLoadRequest();
                request.setResourcePath(key.resourcePath);
                request.setPolylineWgs84(key.toPolyline());
                request.setCoreBufferMeters(TILE_SIZE_METERS / 2.0);
                request.setHaloBufferMeters(TILE_OVERLAP_METERS);
                tileGraph = loader.load(request);
                tileCache.put(key.cacheKey, tileGraph);
                log.warn("[ROAD_GRAPH_EXPAND_TILE] cache=MISS key={} segments={} junctions={} restrictions={}",
                        key.cacheKey,
                        tileGraph.getSegments() == null ? 0 : tileGraph.getSegments().size(),
                        tileGraph.getJunctions() == null ? 0 : tileGraph.getJunctions().size(),
                        tileGraph.getRestrictions() == null ? 0 : tileGraph.getRestrictions().size());
            } else {
                cacheHits++;
                log.warn("[ROAD_GRAPH_EXPAND_TILE] cache=HIT key={} segments={} junctions={} restrictions={}",
                        key.cacheKey,
                        tileGraph.getSegments() == null ? 0 : tileGraph.getSegments().size(),
                        tileGraph.getJunctions() == null ? 0 : tileGraph.getJunctions().size(),
                        tileGraph.getRestrictions() == null ? 0 : tileGraph.getRestrictions().size());
            }

            context.tileGraphs.put(key.cacheKey, tileGraph);
            context.loadedTileKeys.add(key.cacheKey);
            context.mergedGraph = merge(context.mergedGraph, tileGraph);
        }
        log.warn("[ROAD_GRAPH_EXPAND] window={} cacheHits={} cacheMisses={} mergedSegments={} mergedJunctions={} mergedRestrictions={}",
                window.getIndex(),
                cacheHits,
                cacheMisses,
                context.mergedGraph.getSegments() == null ? 0 : context.mergedGraph.getSegments().size(),
                context.mergedGraph.getJunctions() == null ? 0 : context.mergedGraph.getJunctions().size(),
                context.mergedGraph.getRestrictions() == null ? 0 : context.mergedGraph.getRestrictions().size());
        return context.mergedGraph;
    }

    private List<Window> buildWindows(List<GeoCoord> points, int windowPointCount, int overlapPointCount) {
        List<Window> windows = new ArrayList<>();
        if (points == null || points.size() < 2) {
            return windows;
        }

        int start = 0;
        while (start < points.size()) {
            int endExclusive = Math.min(points.size(), start + windowPointCount);
            if (endExclusive - start < 2) {
                break;
            }

            List<GeoCoord> slice = new ArrayList<>(points.subList(start, endExclusive));
            Window window = new Window();
            window.index = windows.size();
            window.startIndex = start;
            window.endIndex = endExclusive - 1;
            window.polyline = slice;
            window.headingDegrees = estimateHeading(slice);
            window.coreBox = BBox.fromPolyline(slice, 150.0);
            window.haloBox = BBox.fromPolyline(slice, 350.0);
            windows.add(window);

            if (endExclusive >= points.size()) {
                break;
            }
            start = Math.max(start + 1, endExclusive - overlapPointCount);
        }
        return windows;
    }

    private Double estimateHeading(List<GeoCoord> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        GeoCoord first = points.get(0);
        GeoCoord last = points.get(points.size() - 1);

        double dLon = Math.toRadians(last.getLon() - first.getLon());
        double rLat1 = Math.toRadians(first.getLat());
        double rLat2 = Math.toRadians(last.getLat());
        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2)
                - Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return bearing < 0 ? bearing + 360.0 : bearing;
    }

    private List<TileKey> resolveTiles(BBox bbox, String resourcePath) {
        List<TileKey> result = new ArrayList<>();
        double meanLat = (bbox.minLat + bbox.maxLat) / 2.0;
        double latStep = TILE_SIZE_METERS / 111_000.0;
        double lonStep = TILE_SIZE_METERS / Math.max(1.0, 111_000.0 * Math.cos(Math.toRadians(meanLat)));

        int minX = (int) Math.floor(bbox.minLon / lonStep);
        int maxX = (int) Math.floor(bbox.maxLon / lonStep);
        int minY = (int) Math.floor(bbox.minLat / latStep);
        int maxY = (int) Math.floor(bbox.maxLat / latStep);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                TileKey key = new TileKey();
                key.resourcePath = resourcePath;
                key.minLon = x * lonStep;
                key.maxLon = (x + 1) * lonStep;
                key.minLat = y * latStep;
                key.maxLat = (y + 1) * latStep;
                key.cacheKey = resourcePath + "|" + x + "|" + y;
                result.add(key);
            }
        }
        return result;
    }

    private RoadGraph merge(RoadGraph a, RoadGraph b) {
        if (a == null || a.getSegments().isEmpty()) {
            return b == null ? new RoadGraph() : b;
        }
        if (b == null || b.getSegments().isEmpty()) {
            return a;
        }

        RoadGraph merged = new RoadGraph();
        for (RoadJunction junction : a.getJunctions().values()) {
            merged.addJunction(junction);
        }
        for (RoadJunction junction : b.getJunctions().values()) {
            merged.addJunction(junction);
        }
        for (RoadSegment segment : a.getSegments().values()) {
            merged.addSegment(segment);
        }
        for (RoadSegment segment : b.getSegments().values()) {
            merged.addSegment(segment);
        }
        for (RoadRestriction restriction : a.getRestrictions()) {
            merged.addRestriction(restriction);
        }
        for (RoadRestriction restriction : b.getRestrictions()) {
            merged.addRestriction(restriction);
        }
        merged.buildDerivedStructures();
        return merged;
    }

    public static class LoadContext {
        private String resourcePath;
        private List<Window> windows;
        private Set<String> loadedTileKeys;
        private Map<String, RoadGraph> tileGraphs;
        private RoadGraph mergedGraph;

        public String getResourcePath() { return resourcePath; }
        public List<Window> getWindows() { return windows; }
        public Set<String> getLoadedTileKeys() { return loadedTileKeys; }
        public Map<String, RoadGraph> getTileGraphs() { return tileGraphs; }
        public RoadGraph getMergedGraph() { return mergedGraph; }
    }

    public static class Window {
        private int index;
        private int startIndex;
        private int endIndex;
        private List<GeoCoord> polyline;
        private Double headingDegrees;
        private BBox coreBox;
        private BBox haloBox;

        public int getIndex() { return index; }
        public int getStartIndex() { return startIndex; }
        public int getEndIndex() { return endIndex; }
        public List<GeoCoord> getPolyline() { return polyline; }
        public Double getHeadingDegrees() { return headingDegrees; }
        public BBox getCoreBox() { return coreBox; }
        public BBox getHaloBox() { return haloBox; }
    }

    private static class TileKey {
        private String resourcePath;
        private double minLat;
        private double maxLat;
        private double minLon;
        private double maxLon;
        private String cacheKey;

        private List<GeoCoord> toPolyline() {
            List<GeoCoord> polyline = new ArrayList<>();
            polyline.add(new GeoCoord(minLat, minLon));
            polyline.add(new GeoCoord(maxLat, maxLon));
            return polyline;
        }
    }

    public static class BBox {
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

        BBox expandForward(Double headingDegrees, double expandMeters) {
            double centerLat = (minLat + maxLat) / 2.0;
            double latDelta = expandMeters / 111_000.0;
            double lonDelta = expandMeters / Math.max(1.0, 111_000.0 * Math.cos(Math.toRadians(centerLat)));

            double nextMinLat = minLat;
            double nextMaxLat = maxLat;
            double nextMinLon = minLon;
            double nextMaxLon = maxLon;

            if (headingDegrees == null) {
                return new BBox(minLat - latDelta, maxLat + latDelta, minLon - lonDelta, maxLon + lonDelta);
            }

            double h = (headingDegrees % 360.0 + 360.0) % 360.0;
            if (h >= 315 || h < 45) {
                nextMaxLat += latDelta;
            } else if (h < 135) {
                nextMaxLon += lonDelta;
            } else if (h < 225) {
                nextMinLat -= latDelta;
            } else {
                nextMinLon -= lonDelta;
            }
            return new BBox(nextMinLat, nextMaxLat, nextMinLon, nextMaxLon);
        }
    }
}
