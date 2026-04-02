package com.travel.travel_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BBox {
    private double minLat;
    private double minLon;
    private double maxLat;
    private double maxLon;
}
