package com.travel.travel_system.controller;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.TripNote;
import com.travel.travel_system.service.TripNoteService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.DateTimeUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class NoteController extends BaseController {

    @Autowired
    private TripNoteService tripNoteService;

    @Autowired
    private TripService tripService;

    @PostMapping("/trips/{tripId}/notes")
    public ApiResponse<?> createNote(@PathVariable Long tripId,
                                     @RequestBody Map<String, Object> request,
                                     HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            Trip trip = tripService.getUserTripOrThrow(userId, tripId);

            String title = asString(request.get("title"));
            String content = asString(request.get("content"));
            String privacyMode = asString(request.get("privacyMode"));
            Long anchorTs = asLong(request.get("anchorTs"));
            Double lat = asDouble(request.get("lat"));
            Double lng = asDouble(request.get("lng"));
            String locationName = asString(request.get("locationName"));

            if (content == null || content.trim().isEmpty()) {
                return error("VALID_001", "笔记内容不能为空");
            }

            TripNote note = new TripNote();
            note.setUserId(userId);
            note.setTripId(tripId);
            note.setTitle(title != null ? title : "笔记");
            note.setContent(content);
            note.setPrivacyMode(privacyMode != null ? privacyMode : "PUBLIC");
            note.setAnchorTs(anchorTs);
            note.setLocationName(locationName);

            if (lat != null && lng != null) {
                note.setLatEnc(encodeDouble(lat));
                note.setLngEnc(encodeDouble(lng));
            }

            TripNote saved = tripNoteService.createNote(note);

            return success(toNoteVO(saved));
        } catch (Exception e) {
            return error("SYSTEM_500", "创建笔记失败：" + e.getMessage());
        }
    }

    @GetMapping("/trips/{tripId}/notes")
    public ApiResponse<?> getNoteList(@PathVariable Long tripId,
                                      @RequestParam(required = false) String keyword,
                                      HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            tripService.getUserTripOrThrow(userId, tripId);

            List<TripNote> notes;
            if (keyword != null && !keyword.trim().isEmpty()) {
                notes = tripNoteService.searchNotesByTitle(tripId, keyword);
            } else {
                notes = tripNoteService.getNotesByTrip(tripId);
            }

            List<Map<String, Object>> items = notes.stream()
                    .map(this::toNoteVO)
                    .collect(Collectors.toList());

            return success(items);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取笔记列表失败：" + e.getMessage());
        }
    }

    @GetMapping("/notes/{noteId}")
    public ApiResponse<?> getNoteDetail(@PathVariable Long noteId, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            TripNote note = tripNoteService.getNote(noteId)
                    .orElseThrow(() -> new RuntimeException("笔记不存在"));

            if (!note.getUserId().equals(userId)) {
                return error("AUTH_003", "无权访问此笔记");
            }

            return success(toNoteVO(note));
        } catch (Exception e) {
            return error("SYSTEM_500", "获取笔记详情失败：" + e.getMessage());
        }
    }

    @PatchMapping("/notes/{noteId}")
    public ApiResponse<?> updateNote(@PathVariable Long noteId,
                                     @RequestBody Map<String, Object> request,
                                     HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            TripNote note = tripNoteService.getNote(noteId)
                    .orElseThrow(() -> new RuntimeException("笔记不存在"));

            if (!note.getUserId().equals(userId)) {
                return error("AUTH_003", "无权修改此笔记");
            }

            String title = asString(request.get("title"));
            String content = asString(request.get("content"));
            String privacyMode = asString(request.get("privacyMode"));
            Long anchorTs = asLong(request.get("anchorTs"));
            Double lat = asDouble(request.get("lat"));
            Double lng = asDouble(request.get("lng"));
            String locationName = asString(request.get("locationName"));

            TripNote updated = tripNoteService.updateNote(noteId, title, content, privacyMode);

            if (anchorTs != null || lat != null || lng != null) {
                byte[] latEnc = lat != null ? encodeDouble(lat) : null;
                byte[] lngEnc = lng != null ? encodeDouble(lng) : null;
                updated = tripNoteService.updateAnchor(noteId, anchorTs, latEnc, lngEnc);
            }

            if (locationName != null) {
                updated.setLocationName(locationName);
            }

            return success(toNoteVO(updated));
        } catch (Exception e) {
            return error("SYSTEM_500", "修改笔记失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/notes/{noteId}")
    public ApiResponse<?> deleteNote(@PathVariable Long noteId, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            TripNote note = tripNoteService.getNote(noteId)
                    .orElseThrow(() -> new RuntimeException("笔记不存在"));

            if (!note.getUserId().equals(userId)) {
                return error("AUTH_003", "无权删除此笔记");
            }

            tripNoteService.deleteNote(noteId);
            return success(Map.of("noteId", noteId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除笔记失败：" + e.getMessage());
        }
    }

    @PatchMapping("/notes/{noteId}/location")
    public ApiResponse<?> updateNoteLocation(@PathVariable Long noteId,
                                             @RequestBody Map<String, Object> request,
                                             HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            TripNote note = tripNoteService.getNote(noteId)
                    .orElseThrow(() -> new RuntimeException("笔记不存在"));

            if (!note.getUserId().equals(userId)) {
                return error("AUTH_003", "无权修改此笔记");
            }

            Double lat = asDouble(request.get("lat"));
            Double lng = asDouble(request.get("lng"));
            String locationName = asString(request.get("locationName"));

            if (lat == null || lng == null) {
                return error("VALID_002", "坐标不能为空");
            }

            byte[] latEnc = encodeDouble(lat);
            byte[] lngEnc = encodeDouble(lng);

            TripNote updated = tripNoteService.updateAnchor(noteId, note.getAnchorTs(), latEnc, lngEnc);
            if (locationName != null) {
                updated.setLocationName(locationName);
            }

            return success(toNoteVO(updated));
        } catch (Exception e) {
            return error("SYSTEM_500", "更新笔记位置失败：" + e.getMessage());
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] encodeDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((bits >> (i * 8)) & 0xFF);
        }
        return bytes;
    }

    private Double decodeDouble(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return null;
        long bits = 0L;
        for (int i = 0; i < 8; i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private Map<String, Object> toNoteVO(TripNote note) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", note.getId());
        vo.put("tripId", note.getTripId());
        vo.put("title", note.getTitle());
        vo.put("content", note.getContent());
        vo.put("privacyMode", note.getPrivacyMode());
        vo.put("anchorTs", note.getAnchorTs());
        vo.put("createdAt", DateTimeUtils.formatDateTime(note.getCreatedAt()));
        vo.put("updatedAt", DateTimeUtils.formatDateTime(note.getUpdatedAt()));

        Double lat = decodeDouble(note.getLatEnc());
        Double lng = decodeDouble(note.getLngEnc());
        if (lat != null && lng != null) {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("lat", lat);
            location.put("lng", lng);
            location.put("name", note.getLocationName());
            vo.put("location", location);
        }

        return vo;
    }
}
