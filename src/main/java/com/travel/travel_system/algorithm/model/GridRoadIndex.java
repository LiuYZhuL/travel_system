package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.*;

public class GridRoadIndex implements Serializable {
    private static final long serialVersionUID = 1L;

    private final double cellSizeMeters;
    private final Map<String, List<RoadSegment>> cells = new HashMap<>();

    public GridRoadIndex(double cellSizeMeters) {
        this.cellSizeMeters = cellSizeMeters;
    }

    public void add(RoadSegment segment) {
        if (segment == null || segment.getGeometry().isEmpty()) {
            return;
        }
        BBox bbox = BBox.ofSegment(segment);
        for (String key : coveredKeys(bbox)) {
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(segment);
        }
    }

    public List<RoadSegment> query(double lat, double lon, double radiusMeters) {
        BBox bbox = BBox.expand(lat, lat, lon, lon, radiusMeters);
        Set<RoadSegment> result = new LinkedHashSet<>();
        for (String key : coveredKeys(bbox)) {
            result.addAll(cells.getOrDefault(key, Collections.emptyList()));
        }
        return new ArrayList<>(result);
    }

    private List<String> coveredKeys(BBox bbox) {
        double meanLat = (bbox.minLat + bbox.maxLat) / 2.0;
        double latStep = cellSizeMeters / 111_000.0;
        double lonStep = cellSizeMeters / Math.max(1.0, 111_000.0 * Math.cos(Math.toRadians(meanLat)));

        int minX = (int) Math.floor(bbox.minLon / lonStep);
        int maxX = (int) Math.floor(bbox.maxLon / lonStep);
        int minY = (int) Math.floor(bbox.minLat / latStep);
        int maxY = (int) Math.floor(bbox.maxLat / latStep);

        List<String> keys = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                keys.add(x + "_" + y);
            }
        }
        return keys;
    }

    @Data
    @AllArgsConstructor
    private static class BBox {
        final double minLat;
        final double maxLat;
        final double minLon;
        final double maxLon;

        static BBox expand(double minLat, double maxLat, double minLon, double maxLon, double bufferMeters) {
            double centerLat = (minLat + maxLat) / 2.0;
            double latDelta = bufferMeters / 111_000.0;
            double lonDelta = bufferMeters / Math.max(1.0, 111_000.0 * Math.cos(Math.toRadians(centerLat)));
            return new BBox(minLat - latDelta, maxLat + latDelta, minLon - lonDelta, maxLon + lonDelta);
        }

        static BBox ofSegment(RoadSegment segment) {
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (GeoCoord p : segment.getGeometry()) {
                minLat = Math.min(minLat, p.getLat());
                maxLat = Math.max(maxLat, p.getLat());
                minLon = Math.min(minLon, p.getLon());
                maxLon = Math.max(maxLon, p.getLon());
            }
            return new BBox(minLat, maxLat, minLon, maxLon);
        }
    }
}
