package com.travel.travel_system.dto;

import com.travel.travel_system.model.TrackPoint;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class TrajectorySegment {
    private List<TrackPoint> points;
    private boolean complex;
    private int startWindowIndex;
    private int endWindowIndex;
    private double averageComplexity;
    private List<Double> complexities;
}
