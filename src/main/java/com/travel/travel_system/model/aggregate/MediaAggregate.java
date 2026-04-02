package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.Video;

public class MediaAggregate {
    private Long mediaId;
    private String mediaType; // PHOTO / VIDEO
    private Long tripId;

    private Photo photo;
    private Video video;

    /** 对应地图落点 */
    private Anchor anchor;

    /** 逻辑归属地点，可空 */
    private PlaceSummary placeSummary;

    /** 是否可作为封面候选 */
    private Boolean coverCandidate;
}