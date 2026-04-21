package com.travel.travel_system.controller;

import com.travel.travel_system.model.Video;
import com.travel.travel_system.service.VideoService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.DateTimeUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/videos")
public class VideoController extends BaseController {

    @Autowired
    private VideoService videoService;

    @PostMapping("/upload")
    public ApiResponse<?> uploadVideo(@RequestParam Long tripId,
                                      @RequestParam(required = false) String userCaption,
                                      @RequestParam(required = false) Long capturedAt,
                                      @RequestParam(required = false) Double lat,
                                      @RequestParam(required = false) Double lng,
                                      @RequestParam(required = false) String locationName,
                                      @RequestParam(required = false) String locationMode,
                                      @RequestParam(required = false) String coordType,
                                      @RequestParam MultipartFile file,
                                      HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);

            if (file.isEmpty()) {
                return error("VALID_002", "文件不能为空");
            }

            Video video = videoService.uploadVideo(tripId, file, userCaption);
            videoService.processVideoAsync(video.getId());
            video = videoService.getVideo(video.getId());

            if (capturedAt != null || lat != null || lng != null || locationName != null || locationMode != null) {
                videoService.updateVideoAssistInfo(
                        video.getId(),
                        capturedAt,
                        lat,
                        lng,
                        normalizeCoordType(coordType),
                        locationName,
                        normalizeLocationMode(locationMode)
                );
                video = videoService.getVideo(video.getId());
            }

            return success(toVideoVO(video));
        } catch (Exception e) {
            return error("SYSTEM_500", "上传视频失败：" + e.getMessage());
        }
    }

    @GetMapping("/{videoId}/status")
    public ApiResponse<?> getVideoStatus(@PathVariable Long videoId) {
        try {
            Video video = videoService.getVideo(videoId);
            if (video == null) {
                return error("DATA_404", "视频不存在");
            }
            return success(Map.of(
                    "videoId", videoId,
                    "processingStatus", video.getProcessingStatus() != null ? video.getProcessingStatus().name() : null
            ));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取视频状态失败：" + e.getMessage());
        }
    }

    @GetMapping("/{videoId}")
    public ApiResponse<?> getVideoInfo(@PathVariable Long videoId) {
        try {
            Video video = videoService.getVideo(videoId);
            if (video == null) {
                return error("DATA_404", "视频不存在");
            }
            return success(toVideoVO(video));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取视频详情失败：" + e.getMessage());
        }
    }

    @GetMapping("/{videoId}/anchor")
    public ApiResponse<?> getVideoAnchor(@PathVariable Long videoId) {
        try {
            Video video = videoService.getVideoAnchor(videoId);
            if (video == null) {
                return error("DATA_404", "视频不存在");
            }
            return success(toVideoVO(video));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取视频锚点失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{videoId}/info")
    public ApiResponse<?> updateVideoInfo(@PathVariable Long videoId,
                                          @RequestBody Map<String, Object> request,
                                          HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            String userCaption = asString(request.get("userCaption"));
            String privacyMode = asString(request.get("privacyMode"));

            Video video = videoService.updateVideoInfo(videoId, userCaption, privacyMode);
            return success(toVideoVO(video));
        } catch (Exception e) {
            return error("SYSTEM_500", "更新视频信息失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<?> deleteVideo(@PathVariable Long videoId, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            videoService.deleteVideo(videoId);
            return success(Map.of("videoId", videoId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除视频失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{videoId}/assist")
    public ApiResponse<?> updateVideoAssist(@PathVariable Long videoId,
                                            @RequestBody Map<String, Object> request,
                                            HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            Video video = videoService.updateVideoAssistInfo(
                    videoId,
                    toLong(request.get("captureTsOverride")),
                    toDouble(request.get("manualLat")),
                    toDouble(request.get("manualLng")),
                    asString(request.get("coordType")),
                    asString(request.get("locationName")),
                    asString(request.get("locationMode"))
            );
            return success(toVideoVO(video));
        } catch (Exception e) {
            return error("SYSTEM_500", "更新视频辅助定位失败：" + e.getMessage());
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private Map<String, Object> toVideoVO(Video video) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", video.getId());
        vo.put("tripId", video.getTripId());
        vo.put("url", video.getObjectKey());
        vo.put("thumbnailUrl", video.getThumbnailObjectKey());
        vo.put("duration", video.getDurationSec());
        vo.put("durationSec", video.getDurationSec());
        vo.put("resolution", video.getResolution());
        vo.put("caption", video.getUserCaption());
        vo.put("shotTime", DateTimeUtils.formatDateTime(video.getShotTimeExif()));
        vo.put("createdAt", DateTimeUtils.formatDateTime(video.getCreatedAt()));
        vo.put("processingStatus", video.getProcessingStatus() != null ? video.getProcessingStatus().name() : null);
        vo.put("captureCoordSource", video.getCaptureCoordSource());
        vo.put("captureCoordType", video.getCaptureCoordType());
        vo.put("locationName", video.getLocationName());
        vo.put("bindingStatus", video.getBindingStatus());
        return vo;
    }

    private String normalizeCoordType(String coordType) {
        if (coordType == null || coordType.trim().isEmpty()) {
            return "GCJ02";
        }
        return coordType.trim().toUpperCase();
    }

    private String normalizeLocationMode(String locationMode) {
        if (locationMode == null || locationMode.trim().isEmpty()) {
            return null;
        }
        return locationMode.trim().toUpperCase();
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
