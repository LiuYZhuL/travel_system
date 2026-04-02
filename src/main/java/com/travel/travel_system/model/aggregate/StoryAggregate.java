package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.*;

import java.util.*;

public class StoryAggregate {
    private Long tripId;
    private Trip trip;

    /** 故事块主列表，已完成排序 */
    private List<StoryBlock> blocks;

    /** 故事块关联的媒体资源 */
    private Map<Long, MediaAggregate> mediaMap;

    /** 故事块关联的地点资源 */
    private Map<Long, PlaceAggregate> placeMap;

    /** 行程AI总结 */
    private TripAiSummary aiSummary;
}