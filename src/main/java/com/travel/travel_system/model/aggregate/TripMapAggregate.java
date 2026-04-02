package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.TripBBox;

import java.util.List;

public class TripMapAggregate {
    private Long tripId;
    private TripBBox tripBBox;

    /** 已处理后的轨迹点，用于画 Polyline */
    private List<TrackPoint> renderTrackPoints;

    /** 起点 */
    private TrackPoint startPoint;

    /** 终点 */
    private TrackPoint endPoint;

    /** 媒体锚点，用于地图 Marker */
    private List<Anchor> mediaAnchors;

    /** 地点摘要，用于地点 Marker */
    private List<PlaceSummary> placeSummaries;
}