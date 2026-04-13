package com.travel.travel_system.controller;

import com.travel.travel_system.service.TripService;
import com.travel.travel_system.utils.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/trips")
public class PublicTripController extends BaseController {

    @Autowired
    private TripService tripService;

    @GetMapping("/{tripId}/share")
    public ApiResponse<?> getSharedTripDetail(@PathVariable Long tripId) {
        try {
            return success(tripService.getPublicTripDetail(tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取分享页失败：" + e.getMessage());
        }
    }
}
