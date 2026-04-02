package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserHomeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private UserProfileVO profile;
    private UserStatsVO stats;
    private UserTripBriefVO activeTrip;
    private UserTripBriefVO latestTrip;
    private UserPermissionStateVO permissionState;
    private UserSettingsVO settings;
    private UserHeatmapVO heatmap;
}
