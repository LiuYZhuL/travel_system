package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import com.travel.travel_system.vo.enums.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripListItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String title;
    private TripStatusVO status;
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
    private String summaryText;
    private PrivacyModeVO privacyMode;
}
