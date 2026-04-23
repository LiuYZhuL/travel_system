package com.travel.travel_system.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private TripSummaryVO trip;
    private TripMapVO map;
    private List<PlaceSummaryVO> places;
    private List<StoryBlockVO> storyBlocks;
    private TripAISummaryVO aiSummary;
    private Boolean shareAllowed;
    private String shareMode;
    private String shareHint;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripSummaryVO implements Serializable {
        private static final long serialVersionUID = 1L;

        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String title;
        private TripStatusVO status;
        private PrivacyModeVO privacyMode;
        private String summaryText;
        private MediaAssetVO cover;
        private String startTime;
        private String endTime;
        private Long distanceM;
        private String distanceText;
        private Long durationSec;
        private String durationText;
        private Integer photoCount;
        private Integer videoCount;
        private Integer placeCount;
    }
}
