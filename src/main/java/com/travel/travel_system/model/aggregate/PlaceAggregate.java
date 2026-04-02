package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.TripNote;

import java.util.List;

public class PlaceAggregate {
    /** 地点摘要主记录 */
    private PlaceSummary place;

    /** 地点封面媒体 */
    private MediaAggregate coverMedia;

    /** 地点下全部媒体 */
    private List<MediaAggregate> medias;

    /** 地点下相关笔记 */
    private List<TripNote> notes;

    /** 地点下相关锚点 */
    private List<Anchor> anchors;
}