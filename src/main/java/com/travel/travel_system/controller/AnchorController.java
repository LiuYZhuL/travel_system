package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AnchorController {

    /**
     * 手动修正媒体锚点
     */
    @PatchMapping("/anchors/{anchorId}")
    public Map<String, Object> updateAnchor(@PathVariable Long anchorId, @RequestBody Map<String, Object> request) {
        return null;
    }
}
