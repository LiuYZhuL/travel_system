package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
@Data
@AllArgsConstructor
public class GeoCoord implements Serializable {
    private static final long serialVersionUID = 1L;

    private final double lat;
    private final double lon;


}
