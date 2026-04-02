package com.travel.travel_system.controller;

import com.travel.travel_system.service.pub.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/photo")
public class PhotoController {

    @Autowired
    private OssService ossService;

    /**
     * 上传照片（Base64）
     */
    @PostMapping("")
    public Map<String, Object> uploadPhotoBase64(@RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 上传照片（Multipart）
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadPhotoMultipart(@RequestParam Long tripId, @RequestParam String userCaption, @RequestParam org.springframework.web.multipart.MultipartFile file) {
        return null;
    }

    /**
     * 获取照片锚点
     */
    @GetMapping("/{photoId}/anchor")
    public Map<String, Object> getPhotoAnchor(@PathVariable Long photoId) {
        return null;
    }
    
    /**
     * 更新照片个性化信息
     */
    @PatchMapping("/{photoId}/info")
    public Map<String, Object> updatePhotoInfo(@PathVariable Long photoId, @RequestBody Map<String, Object> request) {
        return null;
    }
    
    /**
     * 删除照片
     */
    @DeleteMapping("/{photoId}")
    public Map<String, Object> deletePhoto(@PathVariable Long photoId) {
        return null;
    }
}
