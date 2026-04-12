package com.travel.travel_system.utils;

import java.util.List;

public final class GeoUtils {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double PI = Math.PI;
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private GeoUtils() {}

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double rLat1 = Math.toRadians(lat1);
        double rLon1 = Math.toRadians(lon1);
        double rLat2 = Math.toRadians(lat2);
        double rLon2 = Math.toRadians(lon2);
        double dLat = rLat2 - rLat1;
        double dLon = rLon2 - rLon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_METERS * c;
    }

    public static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);
        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2) - Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return (bearing + 360.0) % 360.0;
    }

    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] delta = delta(lat, lon);
        return new double[]{lat + delta[0], lon + delta[1]};
    }

    public static double[] gcj02ToWgs84(double lat, double lon) {
        if (outOfChina(lat, lon)) {
            return new double[]{lat, lon};
        }
        double[] delta = delta(lat, lon);
        double mgLat = lat + delta[0];
        double mgLon = lon + delta[1];
        return new double[]{lat * 2 - mgLat, lon * 2 - mgLon};
    }

    public static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double[] delta(double lat, double lon) {
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{dLat, dLon};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    public static double metersPerDegreeLng(double lat) {
        return 111_320.0 * Math.cos(Math.toRadians(lat));
    }

    public static double metersPerDegreeLat() {
        return 110_574.0;
    }

    public static double[] offsetByMeters(double lat, double lon, double metersNorth, double metersEast) {
        double newLat = lat + metersNorth / metersPerDegreeLat();
        double newLon = lon + metersEast / metersPerDegreeLng(lat);
        return new double[]{newLat, newLon};
    }

    public static BBox calculateBBox(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            return new BBox(0, 0, 0, 0);
        }
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] pt : points) {
            if (pt != null && pt.length >= 2) {
                minLat = Math.min(minLat, pt[0]);
                maxLat = Math.max(maxLat, pt[0]);
                minLon = Math.min(minLon, pt[1]);
                maxLon = Math.max(maxLon, pt[1]);
            }
        }
        if (minLat == Double.MAX_VALUE) {
            return new BBox(0, 0, 0, 0);
        }
        return new BBox(minLat, maxLat, minLon, maxLon);
    }

    public static BBox expandBBox(BBox bbox, double bufferMeters) {
        if (bbox == null || !bbox.isValid()) {
            return bbox;
        }
        double centerLat = bbox.getCenterLat();
        double latDelta = bufferMeters / metersPerDegreeLat();
        double lonDelta = bufferMeters / metersPerDegreeLng(centerLat);
        return new BBox(
                bbox.getMinLat() - latDelta,
                bbox.getMaxLat() + latDelta,
                bbox.getMinLon() - lonDelta,
                bbox.getMaxLon() + lonDelta
        );
    }

    public static final class BBox {
        private final double minLat;
        private final double maxLat;
        private final double minLon;
        private final double maxLon;

        public BBox(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }

        public double getMinLat() { return minLat; }
        public double getMaxLat() { return maxLat; }
        public double getMinLon() { return minLon; }
        public double getMaxLon() { return maxLon; }

        public double getCenterLat() { return (minLat + maxLat) / 2.0; }
        public double getCenterLon() { return (minLon + maxLon) / 2.0; }

        public boolean isValid() {
            return minLat != 0 || maxLat != 0 || minLon != 0 || maxLon != 0;
        }

        public boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
        }

        public double getDiagonalMeters() {
            return haversineMeters(minLat, minLon, maxLat, maxLon);
        }

        public double getWidthMeters() {
            return haversineMeters(getCenterLat(), minLon, getCenterLat(), maxLon);
        }

        public double getHeightMeters() {
            return haversineMeters(minLat, getCenterLon(), maxLat, getCenterLon());
        }

        public BBox expand(double bufferMeters) {
            return GeoUtils.expandBBox(this, bufferMeters);
        }

        public BBox expandForward(Double headingDegrees, double expandMeters) {
            if (headingDegrees == null) {
                return expand(expandMeters);
            }
            double centerLat = getCenterLat();
            double latDelta = expandMeters / metersPerDegreeLat();
            double lonDelta = expandMeters / metersPerDegreeLng(centerLat);
            double rad = Math.toRadians(headingDegrees);
            double dLat = Math.sin(rad) * latDelta;
            double dLon = Math.cos(rad) * lonDelta;
            double nextMinLat = minLat - latDelta + dLat;
            double nextMaxLat = maxLat + latDelta + dLat;
            double nextMinLon = minLon - lonDelta + dLon;
            double nextMaxLon = maxLon + lonDelta + dLon;
            return new BBox(nextMinLat, nextMaxLat, nextMinLon, nextMaxLon);
        }
    }
}
