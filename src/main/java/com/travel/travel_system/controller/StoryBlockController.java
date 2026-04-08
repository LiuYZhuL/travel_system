package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StoryBlockController extends BaseController {

    /**
     * 新建故事块
     */
    @PostMapping("/trips/{tripId}/story-blocks")
    public ApiResponse<?> createStoryBlock(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "新建故事块接口暂未实现");
    }

    /**
     * 修改故事块
     */
    @PatchMapping("/story-blocks/{blockId}")
    public ApiResponse<?> updateStoryBlock(@PathVariable Long blockId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "修改故事块接口暂未实现");
    }

    /**
     * 删除故事块
     */
    @DeleteMapping("/story-blocks/{blockId}")
    public ApiResponse<?> deleteStoryBlock(@PathVariable Long blockId) {
        return error("SYSTEM_501", "删除故事块接口暂未实现");
    }
}
