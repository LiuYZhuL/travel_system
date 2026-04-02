package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryBlockVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private Long tripId;
    private StoryBlockTypeVO type;
    private String sortTime;
    private String displayTimeText;
    private String locationName;
    private GeoPointVO point;
    private String title;
    private String text;
    private MediaAssetVO coverMedia;
    private List<MediaAssetVO> mediaList;
    private Long placeId;
    private PlaceSummaryVO relatedPlace;
    private List<String> moodTags;
}
