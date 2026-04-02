package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long tripId;
    private String poiName;
    private String city;
    private String district;
    private GeoPointVO centerPoint;
    private String startTime;
    private String endTime;
    private Long durationSec;
    private String durationText;
    private Integer photoCount;
    private Integer videoCount;
    private MediaAssetVO coverMedia;
    private String userNotes;
    private List<String> userTags;
    private PrivacyModeVO privacyLevel;
}
