package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.User;

import java.util.List;

public class UserTravelAggregate {
    private User user;

    /** 用户全部行程，可按时间倒序 */
    private List<Trip> trips;

    /** 最近一次行程 */
    private Trip latestTrip;

    /** 当前进行中的行程 */
    private Trip activeTrip;

    /** 全局统计 */
    private Long totalDistanceM;
    private Long totalDurationSec;
    private Integer totalPhotoCount;
    private Integer totalVideoCount;
}
