package com.travel.travel_system.utils;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.dto.RoadEdge;
import com.travel.travel_system.model.dto.RoadNetwork;
import com.travel.travel_system.service.pub.RoadNetworkService;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class RoadNetworkCoverageInspector {

    private final RoadNetworkService roadNetworkService;

    public RoadNetworkCoverageInspector(RoadNetworkService roadNetworkService) {
        this.roadNetworkService = roadNetworkService;
    }

    public PointCoverageReport inspectPoint(double lat, double lon, double radiusMeters) {
        RoadNetwork network = roadNetworkService.getRoadNetwork(lat, lon, Math.max(1.5, radiusMeters / 1000.0 + 1.0));
        List<RoadEdge> nearby = network.findNearbyEdges(lat, lon, radiusMeters);
        nearby.sort(Comparator.comparingDouble(edge -> edge.distanceToPolyline(lat, lon)));

        PointCoverageReport report = new PointCoverageReport();
        report.setLat(lat);
        report.setLon(lon);
        report.setRadiusMeters(radiusMeters);
        report.setNetworkNodeCount(network.getNodeCount());
        report.setNetworkEdgeCount(network.getEdgeCount());

        List<EdgeProbe> probes = new ArrayList<>();
        for (int i = 0; i < Math.min(10, nearby.size()); i++) {
            RoadEdge edge = nearby.get(i);
            EdgeProbe probe = new EdgeProbe();
            probe.setRoadId(edge.getId());
            probe.setSourceWayId(edge.getSourceWayId());
            probe.setName(edge.getName());
            probe.setType(edge.getType());
            probe.setOneWay(edge.isOneWay());
            probe.setDistanceMeters(edge.distanceToPolyline(lat, lon));
            probe.setShapePointCount(edge.getShapePoints() == null ? 0 : edge.getShapePoints().size());
            probes.add(probe);
        }
        report.setNearbyEdges(probes);
        report.setMinDistanceMeters(probes.isEmpty() ? Double.POSITIVE_INFINITY : probes.get(0).getDistanceMeters());
        return report;
    }

    public TripCoverageSummary inspectTrip(List<TrackPoint> points, double radiusMeters) {
        TripCoverageSummary summary = new TripCoverageSummary();
        if (points == null || points.isEmpty()) {
            return summary;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (TrackPoint point : points) {
            double lat = bytesToDouble(point.getLatEnc());
            double lon = bytesToDouble(point.getLngEnc());
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);
        }

        double centerLat = (minLat + maxLat) / 2.0;
        double centerLon = (minLon + maxLon) / 2.0;
        double radiusKm = Math.max(1.5, greatCircle(minLat, minLon, maxLat, maxLon) / 2000.0 + 1.0);
        RoadNetwork network = roadNetworkService.getRoadNetwork(centerLat, centerLon, radiusKm);

        List<Double> distances = new ArrayList<>();
        List<SuspiciousPoint> suspicious = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            double lat = bytesToDouble(points.get(i).getLatEnc());
            double lon = bytesToDouble(points.get(i).getLngEnc());
            List<RoadEdge> nearby = network.findNearbyEdges(lat, lon, radiusMeters);
            double minDistance = Double.POSITIVE_INFINITY;
            Long roadId = null;
            Long wayId = null;
            if (!nearby.isEmpty()) {
                nearby.sort(Comparator.comparingDouble(edge -> edge.distanceToPolyline(lat, lon)));
                RoadEdge best = nearby.get(0);
                minDistance = best.distanceToPolyline(lat, lon);
                roadId = best.getId();
                wayId = best.getSourceWayId();
            }
            distances.add(minDistance);
            if (Double.isInfinite(minDistance) || minDistance > 40.0) {
                SuspiciousPoint sp = new SuspiciousPoint();
                sp.setIndex(i);
                sp.setLat(lat);
                sp.setLon(lon);
                sp.setNearestDistanceMeters(minDistance);
                sp.setNearestRoadId(roadId);
                sp.setNearestWayId(wayId);
                suspicious.add(sp);
            }
        }

        summary.setTotalPoints(points.size());
        summary.setRadiusMeters(radiusMeters);
        summary.setNetworkNodeCount(network.getNodeCount());
        summary.setNetworkEdgeCount(network.getEdgeCount());
        summary.setP50(percentile(distances, 0.50));
        summary.setP90(percentile(distances, 0.90));
        summary.setP95(percentile(distances, 0.95));
        summary.setP99(percentile(distances, 0.99));
        summary.setSuspiciousPoints(suspicious);
        return summary;
    }

    private double percentile(List<Double> values, double q) {
        List<Double> finite = values.stream().filter(v -> !Double.isInfinite(v) && !Double.isNaN(v)).sorted().toList();
        if (finite.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        int idx = Math.min(finite.size() - 1, Math.max(0, (int) Math.floor((finite.size() - 1) * q)));
        return finite.get(idx);
    }

    private double bytesToDouble(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0.0;
        }
        long bits = 0L;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private double greatCircle(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);
        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371000.0 * c;
    }

    @Data
    public static class PointCoverageReport {
        private double lat;
        private double lon;
        private double radiusMeters;
        private int networkNodeCount;
        private int networkEdgeCount;
        private double minDistanceMeters;
        private List<EdgeProbe> nearbyEdges;
    }

    @Data
    public static class EdgeProbe {
        private Long roadId;
        private Long sourceWayId;
        private String name;
        private String type;
        private boolean oneWay;
        private double distanceMeters;
        private int shapePointCount;
    }

    @Data
    public static class TripCoverageSummary {
        private int totalPoints;
        private double radiusMeters;
        private int networkNodeCount;
        private int networkEdgeCount;
        private double p50;
        private double p90;
        private double p95;
        private double p99;
        private List<SuspiciousPoint> suspiciousPoints;
    }

    @Data
    public static class SuspiciousPoint {
        private int index;
        private double lat;
        private double lon;
        private double nearestDistanceMeters;
        private Long nearestRoadId;
        private Long nearestWayId;
    }
}
