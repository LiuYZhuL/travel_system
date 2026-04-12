package com.travel.travel_system.vo;

import com.travel.travel_system.vo.enums.PrivacyModeVO;
import com.travel.travel_system.vo.enums.TripStatusVO;
import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripMutationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private String title;
    private TripStatusVO status;
    private PrivacyModeVO privacyMode;
    private String startTime;
    private String endTime;
    private String updatedAt;
}
