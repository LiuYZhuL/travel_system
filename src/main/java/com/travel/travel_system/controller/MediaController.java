package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

    /**
     * 获取上传凭证
     */
    @PostMapping("/uploads/policies")
    public Map<String, Object> getUploadPolicy(@RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 登记照片
     */
    @PostMapping("/trips/{tripId}/photos")
    public Map<String, Object> registerPhoto(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 登记视频
     */
    @PostMapping("/trips/{tripId}/videos")
    public Map<String, Object> registerVideo(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 修改照片信息
     */
    @PatchMapping("/photos/{photoId}")
    public Map<String, Object> updatePhoto(@PathVariable Long photoId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 修改视频信息
     */
    @PatchMapping("/videos/{videoId}")
    public Map<String, Object> updateVideo(@PathVariable Long videoId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 删除照片
     */
    @DeleteMapping("/photos/{photoId}")
    public Map<String, Object> deletePhoto(@PathVariable Long photoId) {
        return null;
    }

    /**
     * 删除视频
     */
    @DeleteMapping("/videos/{videoId}")
    public Map<String, Object> deleteVideo(@PathVariable Long videoId) {
        return null;
    }
}
