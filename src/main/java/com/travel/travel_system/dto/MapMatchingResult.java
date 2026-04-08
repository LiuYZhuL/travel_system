package com.travel.travel_system.dto;

import lombok.Data;

@Data
public class MapMatchingResult {
    private Long trackPointId;
    private Double matchedLatitude;
    private Double matchedLongitude;

    /**
     * 兼容旧字段：继续表示 matched segment id。
     */
    private Long matchedRoadId;
    private String matchedRoadName;

    /**
     * 新增：明确区分 segment id 与 OSM way id。
     */
    private Long matchedSegmentId;
    private Long matchedWayId;

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
