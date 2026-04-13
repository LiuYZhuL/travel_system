package com.travel.travel_system.controller;

import com.travel.travel_system.model.Photo;
import com.travel.travel_system.service.PhotoService;
import com.travel.travel_system.service.pub.OssService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.DateTimeUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/photos")
public class PhotoController extends BaseController {

    @Autowired
    private OssService ossService;

    @Autowired
    private PhotoService photoService;

    @PostMapping("")
    public ApiResponse<?> uploadPhotoBase64(@RequestBody Map<String, Object> request,
                                            HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            Long tripId = toLong(request.get("tripId"));
            String fileBase64 = asString(request.get("fileBase64"));
            String fileName = asString(request.get("fileName"));
            String userCaption = asString(request.get("userCaption"));
            Boolean isCover = asBoolean(request.get("isCover"));

            if (tripId == null || fileBase64 == null || fileBase64.isEmpty()) {
                return error("VALID_001", "tripId 和 fileBase64 不能为空");
            }

            Photo photo = photoService.uploadPhoto(tripId, fileName != null ? fileName : "photo.jpg", "image/jpeg", fileBase64);
            if ((userCaption != null && !userCaption.trim().isEmpty()) || isCover != null) {
                photo = photoService.updatePhotoInfo(photo.getId(), userCaption, null, isCover);
            }
            return success(toPhotoVO(photo));
        } catch (Exception e) {
            return error("SYSTEM_500", "上传照片失败：" + e.getMessage());
        }
    }

    @PostMapping("/upload")
    public ApiResponse<?> uploadPhotoMultipart(@RequestParam Long tripId,
                                               @RequestParam(required = false) String userCaption,
                                               @RequestParam(required = false) Boolean isCover,
                                               @RequestParam(required = false) Long capturedAt,
                                               @RequestParam(required = false) Double lat,
                                               @RequestParam(required = false) Double lng,
                                               @RequestParam(required = false) String locationName,
                                               @RequestParam(required = false) String coordType,
                                               @RequestParam MultipartFile file,
                                               HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);

            if (file.isEmpty()) {
                return error("VALID_002", "文件不能为空");
            }

            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            Photo photo = photoService.uploadPhoto(tripId, file.getOriginalFilename(), file.getContentType(), base64);

            if (lat != null && lng != null) {
                photoService.updatePhotoAssistInfo(photo.getId(), capturedAt, lat, lng, normalizeCoordType(coordType));
                photo = photoService.getPhotoAnchor(photo.getId());
            }

            if ((userCaption != null && !userCaption.trim().isEmpty()) || isCover != null) {
                photo = photoService.updatePhotoInfo(photo.getId(), userCaption, null, isCover);
            }

            return success(toPhotoVO(photo));
        } catch (Exception e) {
            return error("SYSTEM_500", "上传照片失败：" + e.getMessage());
        }
    }

    @GetMapping("/{photoId}/anchor")
    public ApiResponse<?> getPhotoAnchor(@PathVariable Long photoId) {
        try {
            Photo photo = photoService.getPhotoAnchor(photoId);
            if (photo == null) {
                return error("DATA_404", "照片不存在");
            }
            return success(toPhotoVO(photo));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取照片锚点失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{photoId}/info")
    public ApiResponse<?> updatePhotoInfo(@PathVariable Long photoId,
                                          @RequestBody Map<String, Object> request,
                                          HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            String userCaption = asString(request.get("userCaption"));
            String privacyMode = asString(request.get("privacyMode"));
            Boolean isCover = asBoolean(request.get("isCover"));

            Photo photo = photoService.updatePhotoInfo(photoId, userCaption, privacyMode, isCover);
            return success(toPhotoVO(photo));
        } catch (Exception e) {
            return error("SYSTEM_500", "更新照片信息失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{photoId}")
    public ApiResponse<?> deletePhoto(@PathVariable Long photoId, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            photoService.deletePhoto(photoId);
            return success(Map.of("photoId", photoId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除照片失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{photoId}/assist")
    public ApiResponse<?> updatePhotoAssist(@PathVariable Long photoId,
                                            @RequestBody Map<String, Object> request,
                                            HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
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

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private Map<String, Object> toPhotoVO(Photo photo) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", photo.getId());
        vo.put("tripId", photo.getTripId());
        vo.put("url", photo.getObjectKey());
        vo.put("thumbnailUrl", photo.getObjectKey());
        vo.put("caption", photo.getUserCaption());
        vo.put("shotTime", DateTimeUtils.formatDateTime(photo.getShotTimeExif()));
        vo.put("createdAt", DateTimeUtils.formatDateTime(photo.getCreatedAt()));
        vo.put("captureCoordSource", photo.getCaptureCoordSource());
        vo.put("captureCoordType", photo.getCaptureCoordType());
        vo.put("bindingStatus", photo.getBindingStatus());
        vo.put("isCover", photo.getIsCover());
        return vo;
    }

    private String normalizeCoordType(String coordType) {
        if (coordType == null || coordType.trim().isEmpty()) {
            return "GCJ02";
        }
        return coordType.trim().toUpperCase();
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
