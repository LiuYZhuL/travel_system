package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTripBriefVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private String title;
    private String coverUrl;
    private String startTime;
    private String endTime;
    private TripStatusVO status;
    private String distanceText;
}