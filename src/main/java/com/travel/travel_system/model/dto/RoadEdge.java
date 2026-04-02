package com.travel.travel_system.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = false, of = {"id"})
public class RoadEdge extends RoadNetworkInfo {
    private long startNodeId;
    private long endNodeId;
    private Set<Long> connectedEdgeIds;
    /** 原始 OSM way id，同一条 way 切分出的短边共享该值 */
    private Long sourceWayId;
    private Integer segmentIndex;
    private double lengthM;
    private String type;
    private boolean oneWay;
    private int maxSpeed;
    /** OSM layer，立交/上下层道路判别用 */
    private int layerLevel;
    /** OSM bridge 标签 */
    private boolean bridge;
    /** OSM tunnel 标签 */
    private boolean tunnel;
    /** 匝道/连接道路（如 motorway_link） */
    private boolean rampLike;
    /**
     * 路段真实几何折线。最少 2 个点。
     * 对于从 OSM 相邻 shape node 切分出的边，这里通常就是 2 个点；
     * 对于保留整条 way 的场景，也可以容纳更多 shape 点。
     */
    private List<GeoPoint> shapePoints = new ArrayList<>();

    public RoadEdge(long id, double startLat, double startLon, double endLat, double endLon) {
        super(Long.valueOf(id), startLat, startLon, endLat, endLon);
        this.shapePoints = new ArrayList<>();
        this.shapePoints.add(new GeoPoint(startLat, startLon));
        this.shapePoints.add(new GeoPoint(endLat, endLon));
        refreshGeometry();
    }

    public void setShapePoints(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            this.shapePoints = new ArrayList<>();
            this.shapePoints.add(new GeoPoint(getStartLat(), getStartLon()));
            this.shapePoints.add(new GeoPoint(getEndLat(), getEndLon()));
        } else {
            this.shapePoints = new ArrayList<>(points);
        }
        refreshGeometry();
    }

    public List<GeoPoint> getShapePoints() {
        return shapePoints == null ? Collections.emptyList() : shapePoints;
    }

    public void refreshGeometry() {
        List<GeoPoint> pts = getShapePoints();
        if (pts.size() >= 2) {
            GeoPoint first = pts.get(0);
            GeoPoint last = pts.get(pts.size() - 1);
            setStartLat(first.lat);
            setStartLon(first.lon);
            setEndLat(last.lat);
            setEndLon(last.lon);
            setDirection(calculateLocalDirectionNearStart());
            this.lengthM = calculatePolylineLengthM(pts);
        } else {
            this.lengthM = calculateGreatCircleDistance(getStartLat(), getStartLon(), getEndLat(), getEndLon());
            refreshDirection();
        }
    }

    public double distanceToPolyline(double lat, double lon) {
        List<GeoPoint> pts = getShapePoints();
        if (pts.size() < 2) {
            return calculateGreatCircleDistance(lat, lon, getStartLat(), getStartLon());
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < pts.size() - 1; i++) {
            min = Math.min(min, distanceToSegment(lat, lon, pts.get(i), pts.get(i + 1)));
        }
        return min;
    }

    public Projection project(double lat, double lon) {
        List<GeoPoint> pts = getShapePoints();
        if (pts.size() < 2) {
            return new Projection(getStartLat(), getStartLon(),
                    calculateGreatCircleDistance(lat, lon, getStartLat(), getStartLon()),
                    0.0,
                    getDirection() == null ? 0.0 : getDirection());
        }

        double bestDistance = Double.MAX_VALUE;
        double bestLat = pts.get(0).lat;
        double bestLon = pts.get(0).lon;
        double bestOffset = 0.0;
        double bestDirection = getDirection() == null ? 0.0 : getDirection();
        double prefix = 0.0;

        for (int i = 0; i < pts.size() - 1; i++) {
            GeoPoint a = pts.get(i);
            GeoPoint b = pts.get(i + 1);
            SegmentProjection projection = projectToSegment(lat, lon, a, b);
            if (projection.distanceMeters < bestDistance) {
                bestDistance = projection.distanceMeters;
                bestLat = projection.projectedLat;
                bestLon = projection.projectedLon;
                bestOffset = prefix + projection.offsetOnSegmentMeters;
                bestDirection = calculateDirection(a.lat, a.lon, b.lat, b.lon);
            }
            prefix += calculateGreatCircleDistance(a.lat, a.lon, b.lat, b.lon);
        }

        return new Projection(bestLat, bestLon, bestDistance, bestOffset, bestDirection);
    }

    public List<GeoPoint> sliceByOffset(double startOffsetMeters, double endOffsetMeters) {
        List<GeoPoint> pts = getShapePoints();
        if (pts.size() < 2) {
            return pts;
        }

        double from = Math.max(0.0, Math.min(lengthM, startOffsetMeters));
        double to = Math.max(0.0, Math.min(lengthM, endOffsetMeters));
        if (to < from) {
            double tmp = from;
            from = to;
            to = tmp;
        }

        List<GeoPoint> result = new ArrayList<>();
        double traversed = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            GeoPoint a = pts.get(i);
            GeoPoint b = pts.get(i + 1);
            double segLen = calculateGreatCircleDistance(a.lat, a.lon, b.lat, b.lon);
            double segStart = traversed;
            double segEnd = traversed + segLen;
            traversed = segEnd;

            if (to < segStart || from > segEnd) {
                continue;
            }

            double localFrom = Math.max(from, segStart) - segStart;
            double localTo = Math.min(to, segEnd) - segStart;
            if (result.isEmpty()) {
                result.add(interpolate(a, b, segLen <= 0 ? 0.0 : localFrom / segLen));
            }
            result.add(interpolate(a, b, segLen <= 0 ? 1.0 : localTo / segLen));
        }

        if (result.isEmpty()) {
            result.add(pts.get(0));
            result.add(pts.get(pts.size() - 1));
        }
        return result;
    }

    public boolean hasStructureTag() {
        return bridge || tunnel || layerLevel != 0;
    }

    public String structureSignature() {
        return layerLevel + ":" + bridge + ":" + tunnel + ":" + rampLike;
    }

    private double calculateLocalDirectionNearStart() {
        List<GeoPoint> pts = getShapePoints();
        if (pts.size() >= 2) {
            return calculateDirection(pts.get(0).lat, pts.get(0).lon, pts.get(1).lat, pts.get(1).lon);
        }
        return getDirection() == null ? 0.0 : getDirection();
    }

    private double calculatePolylineLengthM(List<GeoPoint> pts) {
        double total = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            total += calculateGreatCircleDistance(pts.get(i).lat, pts.get(i).lon, pts.get(i + 1).lat, pts.get(i + 1).lon);
        }
        return total;
    }

    private SegmentProjection projectToSegment(double lat, double lon, GeoPoint a, GeoPoint b) {
        double meanLatRad = Math.toRadians((lat + a.lat + b.lat) / 3.0);
        double meterPerLat = 111_320.0;
        double meterPerLon = Math.cos(meanLatRad) * 111_320.0;

        double px = lon * meterPerLon;
        double py = lat * meterPerLat;
        double x1 = a.lon * meterPerLon;
        double y1 = a.lat * meterPerLat;
        double x2 = b.lon * meterPerLon;
        double y2 = b.lat * meterPerLat;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 0.0) {
            return new SegmentProjection(a.lat, a.lon,
                    calculateGreatCircleDistance(lat, lon, a.lat, a.lon),
                    0.0);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        double projectedLon = projX / meterPerLon;
        double projectedLat = projY / meterPerLat;
        return new SegmentProjection(projectedLat, projectedLon,
                Math.hypot(px - projX, py - projY), Math.sqrt(lenSq) * t);
    }

    private double distanceToSegment(double lat, double lon, GeoPoint a, GeoPoint b) {
        return projectToSegment(lat, lon, a, b).distanceMeters;
    }

    private GeoPoint interpolate(GeoPoint a, GeoPoint b, double t) {
        double ratio = Math.max(0.0, Math.min(1.0, t));
        return new GeoPoint(
                a.lat + (b.lat - a.lat) * ratio,
                a.lon + (b.lon - a.lon) * ratio
        );
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

    @Data
    public static class Projection {
        private final double lat;
        private final double lon;
        private final double distanceMeters;
        private final double offsetMeters;
        private final double localDirectionDegrees;
    }

    @Data
    private static class SegmentProjection {
        private final double projectedLat;
        private final double projectedLon;
        private final double distanceMeters;
        private final double offsetOnSegmentMeters;
    }
}
