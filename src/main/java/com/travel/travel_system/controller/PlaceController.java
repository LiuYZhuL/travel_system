package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/trips")
public class PlaceController {

    /**
     * 获取地点详情
     */
    @GetMapping("/{tripId}/places/{placeId}")
    public Map<String, Object> getPlaceDetail(@PathVariable Long tripId, @PathVariable Long placeId) {
        return null;
    }

    /**
     * 修改地点说明
     */
    @PatchMapping("/{tripId}/places/{placeId}")
    public Map<String, Object> updatePlace(@PathVariable Long tripId, @PathVariable Long placeId, @RequestBody Map<String, Object> request) {
        return null;
    }
}
