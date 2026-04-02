package com.travel.travel_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PointComplexity {
    private double directional;
    private double connectivity;
    private double overall;
    private boolean complex;
}
