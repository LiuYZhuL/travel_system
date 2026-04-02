package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.TrackPoint;

import java.util.List;

public class HeatmapAggregate {
    private Long userId;

    /** 当前用户全部行程的原始轨迹点 */
    private List<TrackPoint> sourcePoints;

    /** 聚合后的热力点 */
    private List<HeatPoint> heatPoints;

    public static class HeatPoint {
        private Double lat;
        private Double lng;
        private Integer weight;
    }
}