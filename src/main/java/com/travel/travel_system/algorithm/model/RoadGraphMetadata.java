package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RoadGraphMetadata implements Serializable {
    private static final long serialVersionUID = 1L;

    private String sourceResourcePath;
    private String buildMode;
    private double queryMinLat;
    private double queryMaxLat;
    private double queryMinLon;
    private double queryMaxLon;
    private double coreBufferMeters;
    private double haloBufferMeters;

    private int junctionCount;
    private int segmentCount;
    private int restrictionCount;
    private int weakComponents;
    private double largestWeakComponentRatio;
    private double totalLengthMeters;
}
