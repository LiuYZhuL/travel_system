package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer tripCount;
    private Long totalDistanceM;
    private String totalDistanceText;
    private Long totalDurationSec;
    private String totalDurationText;
    private Integer totalPhotoCount;
    private Integer totalVideoCount;
}
