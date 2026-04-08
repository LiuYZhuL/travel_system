package com.travel.travel_system.controller;

import com.travel.travel_system.model.Photo;
import com.travel.travel_system.service.PhotoService;
import com.travel.travel_system.service.pub.OssService;
import com.travel.travel_system.utils.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/photo")
public class PhotoController extends BaseController {

    @Autowired
    private OssService ossService;

    @Autowired
    private PhotoService photoService;

    /**
     * 上传照片（Base64）
     */
    @PostMapping("")
    public ApiResponse<?> uploadPhotoBase64(@RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "Base64 上传照片接口暂未实现");
    }

    /**
     * 上传照片（Multipart）
     */
    @PostMapping("/upload")
    public ApiResponse<?> uploadPhotoMultipart(@RequestParam Long tripId,
                                               @RequestParam String userCaption,
                                               @RequestParam MultipartFile file) {
        return error("SYSTEM_501", "Multipart 上传照片接口暂未实现");
    }

    /**
     * 获取照片锚点
     */
    @GetMapping("/{photoId}/anchor")
    public ApiResponse<?> getPhotoAnchor(@PathVariable Long photoId) {
        return error("SYSTEM_501", "获取照片锚点接口暂未实现");
    }

    /**
     * 更新照片个性化信息
     */
    @PatchMapping("/{photoId}/info")
    public ApiResponse<?> updatePhotoInfo(@PathVariable Long photoId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "更新照片个性化信息接口暂未实现");
    }

    /**
     * 删除照片
     */
    @DeleteMapping("/{photoId}")
    public ApiResponse<?> deletePhoto(@PathVariable Long photoId) {
        return error("SYSTEM_501", "删除照片接口暂未实现");
    }

    @PatchMapping("/{photoId}/assist")
    public ApiResponse<?> updatePhotoAssist(@PathVariable Long photoId,
                                            @RequestBody Map<String, Object> request) {
        try {
            Photo photo = photoService.updatePhotoAssistInfo(
                    photoId,
                    toLong(request.get("captureTsOverride")),
                    toDouble(request.get("manualLat")),
                    toDouble(request.get("manualLng")),
                    asString(request.get("coordType"))
            );

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("photoId", photo.getId());
            data.put("bindingStatus", photo.getBindingStatus());
            data.put("captureTimeSource", photo.getCaptureTimeSource());
            data.put("captureCoordSource", photo.getCaptureCoordSource());
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "更新照片辅助定位失败：" + e.getMessage());
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
