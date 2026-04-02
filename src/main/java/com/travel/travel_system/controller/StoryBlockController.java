package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class StoryBlockController {

    /**
     * 新建故事块
     */
    @PostMapping("/trips/{tripId}/story-blocks")
    public Map<String, Object> createStoryBlock(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 修改故事块
     */
    @PatchMapping("/story-blocks/{blockId}")
    public Map<String, Object> updateStoryBlock(@PathVariable Long blockId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 删除故事块
     */
    @DeleteMapping("/story-blocks/{blockId}")
    public Map<String, Object> deleteStoryBlock(@PathVariable Long blockId) {
        return null;
    }
}
