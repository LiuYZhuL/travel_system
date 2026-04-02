package com.travel.travel_system.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RoadNetworkInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private Double startLat;
    private Double startLon;
    private Double endLat;
    private Double endLon;
    private Double direction;
    private Integer nodeDegree;

    public RoadNetworkInfo() {
    }

    public RoadNetworkInfo(Long id, Double startLat, Double startLon,
                           Double endLat, Double endLon) {
        this.id = id;
        this.startLat = startLat;
        this.startLon = startLon;
        this.endLat = endLat;
        this.endLon = endLon;
        this.direction = calculateDirection(startLat, startLon, endLat, endLon);
    }

    protected Double calculateDirection(Double startLat, Double startLon,
                                        Double endLat, Double endLon) {
        if (startLat == null || startLon == null || endLat == null || endLon == null) {
            return 0.0;
        }
        double dLon = Math.toRadians(endLon - startLon);
        double rLat1 = Math.toRadians(startLat);
        double rLat2 = Math.toRadians(endLat);
        double y = Math.sin(dLon) * Math.cos(rLat2);
        double x = Math.cos(rLat1) * Math.sin(rLat2)
                - Math.sin(rLat1) * Math.cos(rLat2) * Math.cos(dLon);
        double bearing = Math.toDegrees(Math.atan2(y, x));
        return bearing < 0 ? bearing + 360.0 : bearing;
    }

    protected void refreshDirection() {
        this.direction = calculateDirection(startLat, startLon, endLat, endLon);
    }
}
