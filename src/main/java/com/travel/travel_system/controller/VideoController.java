package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController extends BaseController {

    /**
     * 上传视频（Multipart）
     */
    @PostMapping("/upload")
    public ApiResponse<?> uploadVideo(@RequestParam Long tripId,
                                      @RequestParam String userCaption,
                                      @RequestParam MultipartFile file) {
        return error("SYSTEM_501", "上传视频接口暂未实现");
    }

    /**
     * 查询视频处理状态
     */
    @GetMapping("/{videoId}/status")
    public ApiResponse<?> getVideoStatus(@PathVariable Long videoId) {
        return error("SYSTEM_501", "查询视频处理状态接口暂未实现");
    }

    /**
     * 获取视频详情
     */
    @GetMapping("/{videoId}")
    public ApiResponse<?> getVideoInfo(@PathVariable Long videoId) {
        return error("SYSTEM_501", "获取视频详情接口暂未实现");
    }

    /**
     * 获取视频锚点
     */
    @GetMapping("/{videoId}/anchor")
    public ApiResponse<?> getVideoAnchor(@PathVariable Long videoId) {
        return error("SYSTEM_501", "获取视频锚点接口暂未实现");
    }

    /**
     * 更新视频个性化信息
     */
    @PatchMapping("/{videoId}/info")
    public ApiResponse<?> updateVideoInfo(@PathVariable Long videoId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "更新视频个性化信息接口暂未实现");
    }

    /**
     * 删除视频
     */
    @DeleteMapping("/{videoId}")
    public ApiResponse<?> deleteVideo(@PathVariable Long videoId) {
        return error("SYSTEM_501", "删除视频接口暂未实现");
    }
}
