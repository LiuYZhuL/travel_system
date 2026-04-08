package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class PlaceController extends BaseController {

    /**
     * 获取地点详情
     */
    @GetMapping("/{tripId}/places/{placeId}")
    public ApiResponse<?> getPlaceDetail(@PathVariable Long tripId, @PathVariable Long placeId) {
        return error("SYSTEM_501", "获取地点详情接口暂未实现");
    }

    /**
     * 修改地点说明
     */
    @PatchMapping("/{tripId}/places/{placeId}")
    public ApiResponse<?> updatePlace(@PathVariable Long tripId,
                                      @PathVariable Long placeId,
                                      @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "修改地点说明接口暂未实现");
    }
}
