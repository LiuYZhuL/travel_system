package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MediaController extends BaseController {

    /**
     * 获取上传凭证
     */
    @PostMapping("/uploads/policies")
    public ApiResponse<?> getUploadPolicy(@RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "获取上传凭证接口暂未实现");
    }

    /**
     * 登记照片
     */
    @PostMapping("/trips/{tripId}/photos")
    public ApiResponse<?> registerPhoto(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "登记照片接口暂未实现");
    }

    /**
     * 登记视频
     */
    @PostMapping("/trips/{tripId}/videos")
    public ApiResponse<?> registerVideo(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "登记视频接口暂未实现");
    }

    /**
     * 修改照片信息
     */
    @PatchMapping("/photos/{photoId}")
    public ApiResponse<?> updatePhoto(@PathVariable Long photoId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "修改照片信息接口暂未实现");
    }

    /**
     * 修改视频信息
     */
    @PatchMapping("/videos/{videoId}")
    public ApiResponse<?> updateVideo(@PathVariable Long videoId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "修改视频信息接口暂未实现");
    }

    /**
     * 删除照片
     */
    @DeleteMapping("/photos/{photoId}")
    public ApiResponse<?> deletePhoto(@PathVariable Long photoId) {
        return error("SYSTEM_501", "删除照片接口暂未实现");
    }

    /**
     * 删除视频
     */
    @DeleteMapping("/videos/{videoId}")
    public ApiResponse<?> deleteVideo(@PathVariable Long videoId) {
        return error("SYSTEM_501", "删除视频接口暂未实现");
    }
}
