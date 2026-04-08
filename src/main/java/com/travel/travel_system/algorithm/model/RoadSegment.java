package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
@Data
@AllArgsConstructor
public class RoadSegment implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long segmentId;
    private final long osmWayId;
    private final int segmentOrdinal;

    private long startNodeId;
    private long endNodeId;

    private String name;
    private String highwayType;
    private boolean oneWay;
    private int speedKph;
    private int layer;
    private boolean bridge;
    private boolean tunnel;
    private boolean ramp;
    private boolean construction;

    private String access;
    private String motorVehicle;
    private String motorcar;
    private String serviceType;
    private String junctionType;

    private double lengthMeters;
    private List<GeoCoord> geometry = new ArrayList<>();

    public RoadSegment(long segmentId, long osmWayId, int segmentOrdinal) {
        this.segmentId = segmentId;
        this.osmWayId = osmWayId;
        this.segmentOrdinal = segmentOrdinal;
    }
    public void setGeometry(List<GeoCoord> geometry) {
        this.geometry = geometry == null ? new ArrayList<>() : new ArrayList<>(geometry);
        recomputeLength();
    }

    public void reverseGeometryAndDirection() {
        List<GeoCoord> reversed = new ArrayList<>(geometry);
        Collections.reverse(reversed);
        geometry = reversed;
        long oldStart = startNodeId;
        startNodeId = endNodeId;
        endNodeId = oldStart;
    }

    public RoadSegment copyAsReverse(long newSegmentId) {
        RoadSegment reverse = new RoadSegment(newSegmentId, this.osmWayId, this.segmentOrdinal);
        reverse.startNodeId = this.endNodeId;
        reverse.endNodeId = this.startNodeId;
        reverse.name = this.name;
        reverse.highwayType = this.highwayType;
        reverse.oneWay = false;
        reverse.speedKph = this.speedKph;
        reverse.layer = this.layer;
        reverse.bridge = this.bridge;
        reverse.tunnel = this.tunnel;
        reverse.ramp = this.ramp;
        reverse.construction = this.construction;
        reverse.access = this.access;
        reverse.motorVehicle = this.motorVehicle;
        reverse.motorcar = this.motorcar;
        reverse.serviceType = this.serviceType;
        reverse.junctionType = this.junctionType;
        List<GeoCoord> reversedGeometry = new ArrayList<>(this.geometry);
        Collections.reverse(reversedGeometry);
        reverse.setGeometry(reversedGeometry);
        return reverse;
    }

    public double distanceTo(double lat, double lon) {
        if (geometry.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < geometry.size() - 1; i++) {
            best = Math.min(best, distanceToSegment(lat, lon, geometry.get(i), geometry.get(i + 1)));
        }
        return best;
    }

    private void recomputeLength() {
        lengthMeters = 0.0;
        for (int i = 0; i < geometry.size() - 1; i++) {
            lengthMeters += haversineMeters(geometry.get(i), geometry.get(i + 1));
        }
    }

    private double distanceToSegment(double lat, double lon, GeoCoord a, GeoCoord b) {
        double meanLatRad = Math.toRadians((lat + a.getLat() + b.getLat()) / 3.0);
        double meterPerLat = 111_320.0;
        double meterPerLon = Math.cos(meanLatRad) * 111_320.0;

        double px = lon * meterPerLon;
        double py = lat * meterPerLat;
        double x1 = a.getLon() * meterPerLon;
        double y1 = a.getLat() * meterPerLat;
        double x2 = b.getLon() * meterPerLon;
        double y2 = b.getLat() * meterPerLat;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 0.0) {
            return haversineMeters(lat, lon, a.getLat(), a.getLon());
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));

        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        return Math.hypot(px - projX, py - projY);
    }

    public static double haversineMeters(GeoCoord a, GeoCoord b) {
        return haversineMeters(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);
        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double aa = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(aa), Math.sqrt(1.0 - aa));
        return 6371000.0 * c;
    }
}
