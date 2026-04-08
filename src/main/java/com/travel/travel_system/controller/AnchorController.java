package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AnchorController extends BaseController {

    /**
     * 手动修正媒体锚点
     */
    @PatchMapping("/anchors/{anchorId}")
    public ApiResponse<?> updateAnchor(@PathVariable Long anchorId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "手动修正媒体锚点接口暂未实现");
    }
}
