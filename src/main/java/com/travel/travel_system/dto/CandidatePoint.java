package com.travel.travel_system.dto;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.dto.RoadEdge;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CandidatePoint {
    private TrackPoint trackPoint;
    private RoadEdge road;
    private double projectedLat;
    private double projectedLon;
    private double distanceMeters;
    private double thetaDegrees;
    private double observationProb;
    private double offsetFromStartMeters;
    private double localDirectionDegrees;

    public Double roadDirection() {
        if (Double.isNaN(localDirectionDegrees)) {
            return road == null ? null : road.getDirection();
        }
        return localDirectionDegrees;
    }
}
