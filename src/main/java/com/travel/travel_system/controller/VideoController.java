package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    /**
     * 上传视频（Multipart）
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadVideo(@RequestParam Long tripId, @RequestParam String userCaption, @RequestParam org.springframework.web.multipart.MultipartFile file) {
        return null;
    }

    /**
     * 查询视频处理状态
     */
    @GetMapping("/{videoId}/status")
    public Map<String, Object> getVideoStatus(@PathVariable Long videoId) {
        return null;
    }

    /**
     * 获取视频详情
     */
    @GetMapping("/{videoId}")
    public Map<String, Object> getVideoInfo(@PathVariable Long videoId) {
        return null;
    }

    /**
     * 获取视频锚点
     */
    @GetMapping("/{videoId}/anchor")
    public Map<String, Object> getVideoAnchor(@PathVariable Long videoId) {
        return null;
    }
    
    /**
     * 更新视频个性化信息
     */
    @PatchMapping("/{videoId}/info")
    public Map<String, Object> updateVideoInfo(@PathVariable Long videoId, @RequestBody Map<String, Object> request) {
        return null;
    }
    
    /**
     * 删除视频
     */
    @DeleteMapping("/{videoId}")
    public Map<String, Object> deleteVideo(@PathVariable Long videoId) {
        return null;
    }
}
