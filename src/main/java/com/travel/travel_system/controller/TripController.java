package com.travel.travel_system.controller;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.service.HeatmapService;
import com.travel.travel_system.service.TripService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    @Autowired
    private TripService tripService;

    @Autowired
    private HeatmapService heatmapService;

    @PostMapping("")
    public Map<String, Object> createTrip(@RequestBody Map<String, Object> request, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            String title = asString(request.get("title"));
            if (title == null || title.trim().isEmpty()) {
                return error("VALID_001", "行程标题不能为空");
            }
            Trip trip = tripService.createTrip(
                    userId,
                    title,
                    asString(request.get("timezone")),
                    asString(request.get("privacyMode")),
                    asString(request.get("startTime"))
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tripId", trip.getId());
            data.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "创建行程失败：" + e.getMessage());
        }
    }

    @GetMapping("")
    public Map<String, Object> getTripList(HttpServletRequest request,
                                           @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                           @RequestParam(required = false, defaultValue = "10") Integer pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate,
                                           @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
                                           @RequestParam(required = false, defaultValue = "DESC") String sortOrder) {
        try {
            Long userId = requireUserId(request);
            Pageable pageable = PageRequest.of(
                    Math.max(pageNo - 1, 0),
                    Math.max(pageSize, 1),
                    "DESC".equalsIgnoreCase(sortOrder) ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending()
            );
            Page<Trip> tripPage = tripService.searchUserTrips(userId, pageable, keyword, status, startDate, endDate);
            List<Map<String, Object>> items = tripPage.getContent().stream().map(trip -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("tripId", trip.getId());
                item.put("title", trip.getTitle());
                item.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
                item.put("startTime", formatDateTime(trip.getStartTime()));
                item.put("endTime", formatDateTime(trip.getEndTime()));
                item.put("distanceM", trip.getDistanceM() != null ? trip.getDistanceM() : 0L);
                item.put("distanceText", formatDistance(trip.getDistanceM()));
                item.put("durationSec", trip.getDurationSec() != null ? trip.getDurationSec() : 0L);
                item.put("photoCount", trip.getPhotoCount() != null ? trip.getPhotoCount() : 0);
                item.put("videoCount", trip.getVideoCount() != null ? trip.getVideoCount() : 0);
                item.put("privacyMode", trip.getPrivacyMode() != null ? trip.getPrivacyMode().name() : null);
                return item;
            }).collect(Collectors.toList());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", items);
            data.put("total", tripPage.getTotalElements());
            data.put("pageNo", pageNo);
            data.put("pageSize", pageSize);
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取行程列表失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/detail")
    public Map<String, Object> getTripDetail(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTripDetail(requireUserId(request), tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取行程详情失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{tripId}")
    public Map<String, Object> updateTrip(@PathVariable Long tripId,
                                          @RequestBody Map<String, Object> request,
                                          HttpServletRequest httpRequest) {
        try {
            Trip trip = tripService.updateTripBasic(
                    requireUserId(httpRequest),
                    tripId,
                    asString(request.get("title")),
                    asString(request.get("privacyMode"))
            );
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tripId", trip.getId());
            data.put("title", trip.getTitle());
            data.put("privacyMode", trip.getPrivacyMode() != null ? trip.getPrivacyMode().name() : null);
            data.put("updatedAt", formatDateTime(trip.getUpdatedAt()));
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "修改行程失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/pause")
    public Map<String, Object> pauseTrip(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            tripService.getUserTripOrThrow(requireUserId(request), tripId);
            Trip trip = tripService.pauseTrip(tripId);
            return success(Collections.singletonMap("status", trip.getStatus() != null ? trip.getStatus().name() : null));
        } catch (Exception e) {
            return error("SYSTEM_500", "暂停行程失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/resume")
    public Map<String, Object> resumeTrip(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            tripService.getUserTripOrThrow(requireUserId(request), tripId);
            Trip trip = tripService.resumeTrip(tripId);
            return success(Collections.singletonMap("status", trip.getStatus() != null ? trip.getStatus().name() : null));
        } catch (Exception e) {
            return error("SYSTEM_500", "恢复行程失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/finish")
    public Map<String, Object> finishTrip(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            tripService.getUserTripOrThrow(requireUserId(request), tripId);
            Trip trip = tripService.finishTrip(tripId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tripId", trip.getId());
            data.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
            data.put("endTime", formatDateTime(trip.getEndTime()));
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "结束行程失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{tripId}")
    public Map<String, Object> deleteTrip(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            tripService.getUserTripOrThrow(requireUserId(request), tripId);
            tripService.deleteTrip(tripId);
            return success(Collections.singletonMap("tripId", tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除行程失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/map")
    public Map<String, Object> getTripMap(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTripMap(requireUserId(request), tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取地图数据失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/heatmap")
    public Map<String, Object> getTripHeatmap(@PathVariable Long tripId,
                                              HttpServletRequest request,
                                              @RequestParam(required = false) Integer gridMeters) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            return success(heatmapService.buildTripHeatmap(tripId, gridMeters));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取行程热力图失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/places")
    public Map<String, Object> getTripPlaces(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTripPlaces(requireUserId(request), tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取地点列表失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/story-blocks")
    public Map<String, Object> getTripStoryBlocks(@PathVariable Long tripId,
                                                  HttpServletRequest request,
                                                  @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                                  @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        try {
            return success(tripService.getTripStoryBlocks(requireUserId(request), tripId, pageNo, pageSize));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取故事流失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/statistics")
    public Map<String, Object> getTripStatistics(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            return success(tripService.getTripStatistics(tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取行程统计失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/story")
    public Map<String, Object> getTripStory(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            return success(tripService.getTripStory(tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取行程故事失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/ai-summary")
    public Map<String, Object> getTripAiSummary(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTripAiSummary(requireUserId(request), tripId, false));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取AI总结失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/ai-summary/regenerate")
    public Map<String, Object> regenerateTripAiSummary(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTripAiSummary(requireUserId(request), tripId, true));
        } catch (Exception e) {
            return error("SYSTEM_500", "重新生成AI总结失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/story-blocks/rebuild")
    public Map<String, Object> rebuildTripStoryBlocks(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.rebuildTripStoryBlocks(requireUserId(request), tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "重建故事流失败：" + e.getMessage());
        }
    }

    @GetMapping("/active")
    public Map<String, Object> getActiveTrip(HttpServletRequest request) {
        try {
            return success(tripService.getActiveTrip(requireUserId(request)));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取进行中行程失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/track-points/batch")
    public Map<String, Object> uploadTrackPoints(@PathVariable Long tripId,
                                                 @RequestBody Map<String, Object> request,
                                                 HttpServletRequest httpRequest) {
        try {
            Object pointsObj = request.get("points");
            if (!(pointsObj instanceof List<?> rawList)) {
                return error("VALID_004", "points 不能为空");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) rawList;
            Integer uploadedCount = tripService.uploadTrackPoints(requireUserId(httpRequest), tripId, points);
            return success(Collections.singletonMap("uploadedCount", uploadedCount));
        } catch (Exception e) {
            return error("SYSTEM_500", "上传轨迹点失败：" + e.getMessage());
        }
    }

    @GetMapping("/{tripId}/track-status")
    public Map<String, Object> getTrackStatus(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            return success(tripService.getTrackStatus(requireUserId(request), tripId));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取轨迹状态失败：" + e.getMessage());
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "0");
        response.put("message", "success");
        response.put("data", data);
        response.put("requestId", UUID.randomUUID().toString());
        return response;
    }

    private Map<String, Object> error(String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("requestId", UUID.randomUUID().toString());
        return response;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String formatDateTime(Date date) {
        return date == null ? null : DATE_FORMAT.format(date);
    }

    private String formatDistance(Long meters) {
        if (meters == null || meters <= 0) {
            return "0 m";
        }
        if (meters >= 1000) {
            return String.format(Locale.ROOT, "%.1f km", meters / 1000.0);
        }
        return meters + " m";
    }
}
