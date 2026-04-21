package com.travel.travel_system.controller;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.TripNote;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.TripNoteService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.DateTimeUtils;
import com.travel.travel_system.utils.GeoUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class NoteController extends BaseController {

    @Autowired
    private TripNoteService tripNoteService;

    @Autowired
    private TripService tripService;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

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
            String coordType = normalizeCoordType(asString(request.get("coordType")));
            List<Long> photoIds = asLongList(request.get("photoIds"));
            List<Long> videoIds = asLongList(request.get("videoIds"));
            Integer pendingMediaCount = asInteger(request.get("pendingMediaCount"));

            boolean hasMediaInput = !photoIds.isEmpty() || !videoIds.isEmpty() || (pendingMediaCount != null && pendingMediaCount > 0);
            if ((content == null || content.trim().isEmpty()) && !hasMediaInput) {
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
                note.setCoordinateSource("MANUAL");
                note.setCoordType(coordType);
            }

            TripNote saved = tripNoteService.createNote(note);
            syncNoteMedia(saved, photoIds, videoIds);
            saved = applyMediaDefaultsIfAbsent(saved);

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
            String coordType = normalizeCoordType(asString(request.get("coordType")));
            List<Long> photoIds = asLongList(request.get("photoIds"));
            List<Long> videoIds = asLongList(request.get("videoIds"));
            Integer pendingMediaCount = asInteger(request.get("pendingMediaCount"));

            boolean hasMediaInput = !photoIds.isEmpty() || !videoIds.isEmpty() || (pendingMediaCount != null && pendingMediaCount > 0);
            if ((content == null || content.trim().isEmpty()) && !hasMediaInput && note.getContent() == null) {
                return error("VALID_001", "笔记内容不能为空");
            }

            TripNote updated = tripNoteService.updateNote(noteId, title, content, privacyMode);

            if (anchorTs != null || lat != null || lng != null) {
                byte[] latEnc = lat != null ? encodeDouble(lat) : null;
                byte[] lngEnc = lng != null ? encodeDouble(lng) : null;
                updated = tripNoteService.updateAnchor(noteId, anchorTs, latEnc, lngEnc);
            }

            if (locationName != null || (lat != null && lng != null)) {
                updated = tripNoteService.updateLocation(noteId, lat, lng, locationName, coordType);
            }
            syncNoteMedia(updated, photoIds, videoIds);
            updated = applyMediaDefaultsIfAbsent(updated);

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

            detachNoteMedia(noteId);
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
            String coordType = normalizeCoordType(asString(request.get("coordType")));

            if (lat == null || lng == null) {
                return error("VALID_002", "坐标不能为空");
            }

            byte[] latEnc = encodeDouble(lat);
            byte[] lngEnc = encodeDouble(lng);

            TripNote updated = tripNoteService.updateAnchor(noteId, note.getAnchorTs(), latEnc, lngEnc);
            updated = tripNoteService.updateLocation(noteId, lat, lng, locationName, coordType);

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

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Long> asLongList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            Long parsed = asLong(item);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
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
            location.put("coordType", note.getCoordType());
            vo.put("location", location);
        }

        List<Map<String, Object>> mediaItems = new ArrayList<>();
        for (Photo photo : photoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            mediaItems.add(toPhotoMediaItem(photo));
        }
        for (Video video : videoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            mediaItems.add(toVideoMediaItem(video));
        }
        mediaItems.sort((a, b) -> {
            Long timeA = asLong(a.get("createdAt"));
            Long timeB = asLong(b.get("createdAt"));
            if (timeA == null) timeA = 0L;
            if (timeB == null) timeB = 0L;
            return timeA.compareTo(timeB);
        });
        vo.put("media", mediaItems);
        vo.put("mediaCount", mediaItems.size());

        return vo;
    }

    private void syncNoteMedia(TripNote note, List<Long> photoIds, List<Long> videoIds) {
        if (note == null || note.getId() == null) {
            return;
        }
        Set<Long> selectedPhotoIds = new HashSet<>(photoIds == null ? Collections.emptyList() : photoIds);
        Set<Long> selectedVideoIds = new HashSet<>(videoIds == null ? Collections.emptyList() : videoIds);

        for (Photo photo : photoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            if (!selectedPhotoIds.contains(photo.getId())) {
                photo.setNoteId(null);
                photoRepository.save(photo);
            }
        }
        for (Video video : videoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            if (!selectedVideoIds.contains(video.getId())) {
                video.setNoteId(null);
                videoRepository.save(video);
            }
        }

        if (!selectedPhotoIds.isEmpty()) {
            List<Photo> photos = photoRepository.findAllById(selectedPhotoIds);
            for (Photo photo : photos) {
                if (Objects.equals(photo.getUserId(), note.getUserId()) && Objects.equals(photo.getTripId(), note.getTripId())) {
                    photo.setNoteId(note.getId());
                    photoRepository.save(photo);
                }
            }
        }
        if (!selectedVideoIds.isEmpty()) {
            List<Video> videos = videoRepository.findAllById(selectedVideoIds);
            for (Video video : videos) {
                if (Objects.equals(video.getUserId(), note.getUserId()) && Objects.equals(video.getTripId(), note.getTripId())) {
                    video.setNoteId(note.getId());
                    videoRepository.save(video);
                }
            }
        }
    }

    private void detachNoteMedia(Long noteId) {
        for (Photo photo : photoRepository.findByNoteIdOrderByCreatedAtAsc(noteId)) {
            photo.setNoteId(null);
            photoRepository.save(photo);
        }
        for (Video video : videoRepository.findByNoteIdOrderByCreatedAtAsc(noteId)) {
            video.setNoteId(null);
            videoRepository.save(video);
        }
    }

    private Map<String, Object> toPhotoMediaItem(Photo photo) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", photo.getId());
        item.put("type", "photo");
        item.put("url", photo.getObjectKey());
        item.put("thumbnailUrl", photo.getObjectKey());
        item.put("caption", photo.getUserCaption());
        item.put("locationName", photo.getLocationName());
        item.put("capturedAt", resolvePhotoCapturedAt(photo));
        item.put("createdAt", photo.getCreatedAt() != null ? photo.getCreatedAt().getTime() : null);
        MediaLocation location = resolvePhotoLocation(photo);
        if (location != null) {
            item.put("location", toLocationPayload(location));
        }
        return item;
    }

    private Map<String, Object> toVideoMediaItem(Video video) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", video.getId());
        item.put("type", "video");
        item.put("url", video.getObjectKey());
        item.put("thumbnailUrl", video.getThumbnailObjectKey());
        item.put("caption", video.getUserCaption());
        item.put("locationName", video.getLocationName());
        item.put("duration", video.getDurationSec());
        item.put("capturedAt", resolveVideoCapturedAt(video));
        item.put("createdAt", video.getCreatedAt() != null ? video.getCreatedAt().getTime() : null);
        MediaLocation location = resolveVideoLocation(video);
        if (location != null) {
            item.put("location", toLocationPayload(location));
        }
        return item;
    }

    private TripNote applyMediaDefaultsIfAbsent(TripNote note) {
        if (note == null || note.getId() == null) {
            return note;
        }
        List<NoteMediaDefaultCandidate> candidates = new ArrayList<>();
        for (Photo photo : photoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            candidates.add(buildPhotoDefaultCandidate(photo));
        }
        for (Video video : videoRepository.findByNoteIdOrderByCreatedAtAsc(note.getId())) {
            candidates.add(buildVideoDefaultCandidate(video));
        }
        candidates.sort((left, right) -> Long.compare(left.sortTs(), right.sortTs()));

        Long anchorTs = candidates.stream()
                .map(NoteMediaDefaultCandidate::captureTs)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        NoteMediaDefaultCandidate locationCandidate = candidates.stream()
                .filter(candidate -> candidate.lat() != null && candidate.lng() != null)
                .findFirst()
                .orElse(null);

        TripNote updated = tripNoteService.applyDefaultAnchorAndLocation(
                note.getId(),
                anchorTs,
                locationCandidate != null ? locationCandidate.lat() : null,
                locationCandidate != null ? locationCandidate.lng() : null,
                locationCandidate != null ? locationCandidate.locationName() : null,
                locationCandidate != null ? locationCandidate.coordType() : null,
                "MEDIA_DEFAULT"
        );
        if ((updated.getLocationName() == null || updated.getLocationName().isBlank()) && locationCandidate != null) {
            updated.setLocationName(locationCandidate.locationName());
        }
        return updated;
    }

    private NoteMediaDefaultCandidate buildPhotoDefaultCandidate(Photo photo) {
        Long captureTs = resolvePhotoCapturedAt(photo);
        long sortTs = captureTs != null ? captureTs : (photo.getCreatedAt() != null ? photo.getCreatedAt().getTime() : Long.MAX_VALUE);
        MediaLocation location = resolvePhotoLocation(photo);
        return new NoteMediaDefaultCandidate(
                sortTs,
                captureTs,
                location != null ? location.lat() : null,
                location != null ? location.lng() : null,
                location != null ? location.name() : null,
                location != null ? location.coordType() : null
        );
    }

    private NoteMediaDefaultCandidate buildVideoDefaultCandidate(Video video) {
        Long captureTs = resolveVideoCapturedAt(video);
        long sortTs = captureTs != null ? captureTs : (video.getCreatedAt() != null ? video.getCreatedAt().getTime() : Long.MAX_VALUE);
        MediaLocation location = resolveVideoLocation(video);
        return new NoteMediaDefaultCandidate(
                sortTs,
                captureTs,
                location != null ? location.lat() : null,
                location != null ? location.lng() : null,
                location != null ? location.name() : null,
                location != null ? location.coordType() : null
        );
    }

    private Long resolvePhotoCapturedAt(Photo photo) {
        if (photo == null) {
            return null;
        }
        if (photo.getCaptureTsOverride() != null) {
            return photo.getCaptureTsOverride();
        }
        return photo.getShotTimeExif() != null ? photo.getShotTimeExif().getTime() : null;
    }

    private Long resolveVideoCapturedAt(Video video) {
        if (video == null) {
            return null;
        }
        if (video.getCaptureTsOverride() != null) {
            return video.getCaptureTsOverride();
        }
        return video.getShotTimeExif() != null ? video.getShotTimeExif().getTime() : null;
    }

    private MediaLocation resolvePhotoLocation(Photo photo) {
        if (photo == null || "NONE".equalsIgnoreCase(photo.getCaptureCoordSource())) {
            return null;
        }
        byte[] latBytes = photo.getCaptureLatOverride() != null ? photo.getCaptureLatOverride() : photo.getLatEnc();
        byte[] lngBytes = photo.getCaptureLngOverride() != null ? photo.getCaptureLngOverride() : photo.getLngEnc();
        return resolveMediaLocation(latBytes, lngBytes, photo.getCaptureCoordType(), photo.getLocationName(), photo.getCaptureCoordSource());
    }

    private MediaLocation resolveVideoLocation(Video video) {
        if (video == null || "NONE".equalsIgnoreCase(video.getCaptureCoordSource())) {
            return null;
        }
        byte[] latBytes = video.getCaptureLatOverride() != null ? video.getCaptureLatOverride() : video.getLatEnc();
        byte[] lngBytes = video.getCaptureLngOverride() != null ? video.getCaptureLngOverride() : video.getLngEnc();
        return resolveMediaLocation(latBytes, lngBytes, video.getCaptureCoordType(), video.getLocationName(), video.getCaptureCoordSource());
    }

    private MediaLocation resolveMediaLocation(byte[] latBytes, byte[] lngBytes, String coordType, String locationName, String coordSource) {
        Double lat = decodeDouble(latBytes);
        Double lng = decodeDouble(lngBytes);
        if (lat == null || lng == null) {
            return null;
        }
        String normalizedCoordType = normalizeMediaCoordType(coordType, coordSource);
        double displayLat = lat;
        double displayLng = lng;
        if ("WGS84".equals(normalizedCoordType)) {
            double[] converted = GeoUtils.wgs84ToGcj02(lat, lng);
            displayLat = converted[0];
            displayLng = converted[1];
            normalizedCoordType = "GCJ02";
        }
        return new MediaLocation(displayLat, displayLng, locationName, normalizedCoordType);
    }

    private Map<String, Object> toLocationPayload(MediaLocation location) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lat", location.lat());
        payload.put("lng", location.lng());
        payload.put("name", location.name());
        payload.put("coordType", location.coordType());
        return payload;
    }

    private String normalizeMediaCoordType(String coordType, String coordSource) {
        if (coordType != null && !coordType.isBlank()) {
            return coordType.trim().toUpperCase();
        }
        if ("EXIF".equalsIgnoreCase(coordSource)) {
            return "WGS84";
        }
        return "GCJ02";
    }

    private String normalizeCoordType(String coordType) {
        if (coordType == null || coordType.trim().isEmpty()) {
            return "GCJ02";
        }
        return coordType.trim().toUpperCase();
    }

    private record MediaLocation(Double lat, Double lng, String name, String coordType) {}
    private record NoteMediaDefaultCandidate(long sortTs, Long captureTs, Double lat, Double lng, String locationName, String coordType) {}
}
