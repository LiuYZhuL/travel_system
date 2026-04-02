package com.travel.travel_system.dto;

import java.io.Serializable;

public class GeoPoint implements Serializable {
    private static final long serialVersionUID = 1L;
    public double lat;
    public double lon;
    public GeoPoint(double lat, double lon) { this.lat = lat; this.lon = lon; }
}