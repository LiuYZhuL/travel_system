package com.travel.travel_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Projection {
    private double lat;
    private double lon;
    private double distanceMeters;
    private double offsetFromStartMeters;
    private double localDirectionDegrees;
}
