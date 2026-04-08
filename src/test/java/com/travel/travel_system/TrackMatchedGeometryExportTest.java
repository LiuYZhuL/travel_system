package com.travel.travel_system;

import com.travel.travel_system.dto.GeoPoint;
import com.travel.travel_system.dto.RoadEdge;
import com.travel.travel_system.dto.MapMatchingResult;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.service.impl.TrackPointServiceImpl;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SpringBootTest
public class TrackMatchedGeometryExportTest {

    private static final long TRIP_ID = 2L;
    private static final int FROM_INDEX = 10;
    private static final int TO_INDEX = 21;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private TrackPointServiceImpl trackPointService;
    private Logger log;

    @Test
    void exportMatchedGeometryForProblemSegment() {
        List<TrackPoint> raw = trackPointRepository.findByTripIdOrderByTsAsc(TRIP_ID);
        List<MapMatchingResult> matched = trackPointService.matchTrajectory(TRIP_ID);

        if (raw == null || raw.isEmpty()) {
            log.info("[MATCH_EXPORT] raw points empty, tripId=" + TRIP_ID);
            return;
        }
        if (matched == null || matched.isEmpty()) {
            log.info("[MATCH_EXPORT] matched results empty, tripId=" + TRIP_ID);
            return;
        }

        int from = Math.max(0, FROM_INDEX);
        int to = Math.min(Math.min(raw.size(), matched.size()) - 1, TO_INDEX);
        if (from > to) {
            log.info("[MATCH_EXPORT] invalid range=" + FROM_INDEX + "~" + TO_INDEX);
            return;
        }

        log.info("====================================================");
        log.info(String.format(Locale.ROOT,
                "[MATCH_EXPORT] tripId=%d inspectRange=%d~%d rawCount=%d matchedCount=%d",
                TRIP_ID, from, to, raw.size(), matched.size()));

        for (int i = from; i <= to; i++) {
            TrackPoint rawPoint = raw.get(i);
            MapMatchingResult result = matched.get(i);
            exportSinglePoint(i, rawPoint, result);
        }

        for (int i = from + 1; i <= to; i++) {
            MapMatchingResult prev = matched.get(i - 1);
            MapMatchingResult curr = matched.get(i);
            exportTransition(i - 1, i, prev, curr);
        }
        log.info("====================================================");
    }

    private void exportSinglePoint(int index, TrackPoint rawPoint, MapMatchingResult result) {
        double rawLat = bytesToDouble(rawPoint.getLatEnc());
        double rawLon = bytesToDouble(rawPoint.getLngEnc());
        double[] normalized = toInternalWgs84(rawLat, rawLon, rawPoint.getRawCoordType());
        RoadEdge road = result == null ? null : result.getMatchedRoad();
        double matchedLat = result == null || result.getMatchedLatitude() == null ? Double.NaN : result.getMatchedLatitude();
        double matchedLon = result == null || result.getMatchedLongitude() == null ? Double.NaN : result.getMatchedLongitude();
        double gap = (Double.isNaN(matchedLat) || Double.isNaN(matchedLon))
                ? Double.NaN
                : haversineMeters(normalized[0], normalized[1], matchedLat, matchedLon);

        log.info(String.format(Locale.ROOT,
                "[MATCH_EXPORT_POINT] idx=%d ts=%d rawType=%s raw=(%.12f,%.12f) normalized=(%.12f,%.12f) matched=(%.12f,%.12f) snapGap=%.2f road=%s",
                index,
                safeTs(rawPoint),
                rawPoint.getRawCoordType(),
                rawLat,
                rawLon,
                normalized[0],
                normalized[1],
                matchedLat,
                matchedLon,
                gap,
                describeRoad(road)));

        if (road != null) {
            List<GeoPoint> shape = road.getShapePoints();
            log.info(String.format(Locale.ROOT,
                    "[MATCH_EXPORT_GEOM] idx=%d way=%s seg=%s shapeCount=%d shape=%s",
                    index,
                    road.getSourceWayId(),
                    road.getSegmentIndex(),
                    shape == null ? 0 : shape.size(),
                    formatShapePoints(shape)));
        }
    }

    private void exportTransition(int prevIndex,
                                  int currIndex,
                                  MapMatchingResult prev,
                                  MapMatchingResult curr) {
        RoadEdge prevRoad = prev == null ? null : prev.getMatchedRoad();
        RoadEdge currRoad = curr == null ? null : curr.getMatchedRoad();
        if (prevRoad == null || currRoad == null) {
            log.info(String.format(Locale.ROOT,
                    "[MATCH_EXPORT_EDGE] %d->%d missingRoad prev=%s curr=%s",
                    prevIndex,
                    currIndex,
                    describeRoad(prevRoad),
                    describeRoad(currRoad)));
            return;
        }

        boolean sameRoad = prevRoad.getId() != null && prevRoad.getId().equals(currRoad.getId());
        boolean sameWay = prevRoad.getSourceWayId() != null
                && prevRoad.getSourceWayId().equals(currRoad.getSourceWayId());
        boolean shareNode = prevRoad.getStartNodeId() == currRoad.getStartNodeId()
                || prevRoad.getStartNodeId() == currRoad.getEndNodeId()
                || prevRoad.getEndNodeId() == currRoad.getStartNodeId()
                || prevRoad.getEndNodeId() == currRoad.getEndNodeId();
        double matchedGap = haversineMeters(
                prev.getMatchedLatitude(), prev.getMatchedLongitude(),
                curr.getMatchedLatitude(), curr.getMatchedLongitude());

        log.info(String.format(Locale.ROOT,
                "[MATCH_EXPORT_EDGE] %d->%d matchedGap=%.2f sameRoad=%s sameWay=%s shareNode=%s prev=%s curr=%s",
                prevIndex,
                currIndex,
                matchedGap,
                sameRoad,
                sameWay,
                shareNode,
                describeRoad(prevRoad),
                describeRoad(currRoad)));
    }

    private String describeRoad(RoadEdge road) {
        if (road == null) {
            return "<null>";
        }
        return String.format(Locale.ROOT,
                "%s|%s|layer=%d|bridge=%s|ramp=%s|oneway=%s|way=%s|seg=%s|start=(%.6f,%.6f)|end=(%.6f,%.6f)",
                road.getName(),
                road.getType(),
                road.getLayerLevel(),
                road.isBridge(),
                road.isRampLike(),
                road.isOneWay(),
                road.getSourceWayId(),
                road.getSegmentIndex(),
                road.getStartLat(),
                road.getStartLon(),
                road.getEndLat(),
                road.getEndLon());
    }

    private String formatShapePoints(List<GeoPoint> points) {
        if (points == null || points.isEmpty()) {
            return "[]";
        }
        List<String> out = new ArrayList<>();
        for (GeoPoint p : points) {
            out.add(String.format(Locale.ROOT, "(%.6f,%.6f)", p.lat, p.lon));
        }
        return out.toString();
    }

    private long safeTs(TrackPoint point) {
        return point == null || point.getTs() == null ? 0L : point.getTs();
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

    private double[] toInternalWgs84(double lat, double lon, CoordType sourceType) {
        if (sourceType == null || sourceType == CoordType.WGS84) {
            return new double[]{lat, lon};
        }
        if (sourceType == CoordType.GCJ02) {
            return gcj02ToWgs84(lat, lon);
        }
        return new double[]{lat, lon};
    }

    private double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] gcj = wgs84ToGcj02(lat, lon);
        return new double[]{lat * 2 - gcj[0], lon * 2 - gcj[1]};
    }

    private double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
        dLon = (dLon * 180.0) / ((6378245.0 / sqrtMagic) * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return r * c;
    }
}
