package com.travel.travel_system.dto;

import lombok.Data;

@Data
public class MapMatchingResult {
    private Long trackPointId;
    private Double matchedLatitude;
    private Double matchedLongitude;
    private Long matchedRoadId;
    private String matchedRoadName;
    private RoadEdge matchedRoad;
    private Double confidence;
    private Integer position;
    
    public MapMatchingResult() {
    }
    
    public MapMatchingResult(Long trackPointId, Double matchedLatitude, 
                            Double matchedLongitude, Double confidence) {
        this.trackPointId = trackPointId;
        this.matchedLatitude = matchedLatitude;
        this.matchedLongitude = matchedLongitude;
        this.confidence = confidence;
    }
}
