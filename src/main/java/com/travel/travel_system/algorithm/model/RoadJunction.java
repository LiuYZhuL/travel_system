package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

@Data
@AllArgsConstructor
public class RoadJunction implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long id;
    private final double lat;
    private final double lon;
    private int degree;

    public RoadJunction(long id, double lat, double lon) {
        this.id = id;
        this.lat = lat;
        this.lon = lon;
    }
    public void increaseDegree() {
        this.degree++;
    }

}
