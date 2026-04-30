package com.travel.travel_system.controller;

import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.TripNote;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.PhotoService;
import com.travel.travel_system.service.TripNoteService;
import com.travel.travel_system.service.VideoService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.DateTimeUtils;
import com.travel.travel_system.utils.GeoUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController extends BaseController {

    @Autowired
    private PhotoService photoService;

    @Autowired
    private VideoService videoService;

    @Autowired
    private TripNoteService tripNoteService;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @PostMapping("/upload")
    public ApiResponse<?> uploadMedia(@RequestParam Long tripId,
                                      @RequestParam(required = false) Long noteId,
                                      @RequestParam(required = false) String userCaption,
                                      @RequestParam(required = false) Boolean isCover,
                                      @RequestParam(required = false) Long capturedAt,
                                      @RequestParam(required = false) Double lat,
                                      @RequestParam(required = false) Double lng,
                                      @RequestParam(required = false) String locationName,
                                      @RequestParam(required = false) String locationMode,
                                      @RequestParam(required = false) String coordType,
                                      @RequestParam(required = false) String mediaTypeHint,
                                      @RequestParam MultipartFile file,
                                      HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            TripNote note = validateNoteBinding(userId, tripId, noteId);
            if (file == null || file.isEmpty()) {
                return error("VALID_002", "文件不能为空");
            }

            // 优先从文件自身检测类型；检测失败时使用前端传入的 mediaTypeHint 作为兜底
            // （微信小程序 uni.uploadFile 上传视频时 Content-Type 可能为 application/octet-stream，文件名也无扩展名）
            String mediaType = detectMediaType(file);
            if (mediaType == null && mediaTypeHint != null) {
                String hint = mediaTypeHint.toLowerCase(Locale.ROOT).trim();
                if (hint.equals("video")) mediaType = "VIDEO";
                else if (hint.equals("image") || hint.equals("photo")) mediaType = "PHOTO";
            }
            if ("PHOTO".equals(mediaType)) {
                byte[] bytes = file.getBytes();
                String base64 = Base64.getEncoder().encodeToString(bytes);
                Photo photo = photoService.uploadPhoto(tripId, file.getOriginalFilename(), file.getContentType(), base64);
                if (capturedAt != null || lat != null || lng != null || locationName != null || locationMode != null) {
                    photo = photoService.updatePhotoAssistInfo(
                            photo.getId(),
                            capturedAt,
                            lat,
                            lng,
                            normalizeCoordType(coordType),
                            locationName,
                            normalizeLocationMode(locationMode)
                    );
                }
                if ((userCaption != null && !userCaption.trim().isEmpty()) || isCover != null) {
                    photo = photoService.updatePhotoInfo(photo.getId(), userCaption, null, isCover);
                }
                if (note != null) {
                    photo.setNoteId(note.getId());
                    photo = photoRepository.save(photo);
                    applyNoteDefaultsFromPhoto(note.getId(), photo);
                }
                return success(toPhotoPayload(photo));
            }

            if ("VIDEO".equals(mediaType)) {
                Video video = videoService.uploadVideo(tripId, file, userCaption);
                videoService.processVideoAsync(video.getId());
                if (capturedAt != null || lat != null || lng != null || locationName != null || locationMode != null) {
                    video = videoService.updateVideoAssistInfo(
                            video.getId(),
                            capturedAt,
                            lat,
                            lng,
                            normalizeCoordType(coordType),
                            locationName,
                            normalizeLocationMode(locationMode)
                    );
                }
                if (note != null) {
                    video.setNoteId(note.getId());
                    video = videoRepository.save(video);
                    applyNoteDefaultsFromVideo(note.getId(), video);
                }
                return success(toVideoPayload(videoService.getVideo(video.getId())));
            }

            return error("MEDIA_003", "不支持的媒体类型");
        } catch (Exception e) {
            return error("SYSTEM_500", "上传媒体失败：" + e.getMessage());
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private TripNote validateNoteBinding(Long userId, Long tripId, Long noteId) {
        if (noteId == null) {
            return null;
        }
        TripNote note = tripNoteService.getNote(noteId)
                .orElseThrow(() -> new RuntimeException("笔记不存在，noteId: " + noteId));
        if (!note.getUserId().equals(userId)) {
            throw new RuntimeException("无权绑定到该笔记");
        }
        if (!note.getTripId().equals(tripId)) {
            throw new RuntimeException("媒体与笔记不属于同一行程");
        }
        return note;
    }

    private String detectMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("image/")) {
                return "PHOTO";
            }
            if (normalized.startsWith("video/")) {
                return "VIDEO";
            }
        }

        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String normalizedName = fileName.toLowerCase(Locale.ROOT);
            if (normalizedName.endsWith(".jpg") || normalizedName.endsWith(".jpeg") || normalizedName.endsWith(".png")
                    || normalizedName.endsWith(".webp") || normalizedName.endsWith(".heic") || normalizedName.endsWith(".gif")) {
                return "PHOTO";
            }
            if (normalizedName.endsWith(".mp4") || normalizedName.endsWith(".mov") || normalizedName.endsWith(".m4v")
                    || normalizedName.endsWith(".3gp") || normalizedName.endsWith(".webm")) {
                return "VIDEO";
            }
        }
        return null;
    }

    private String normalizeCoordType(String coordType) {
        if (coordType == null || coordType.trim().isEmpty()) {
            return "GCJ02";
        }
        return coordType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeLocationMode(String locationMode) {
        if (locationMode == null || locationMode.trim().isEmpty()) {
            return null;
        }
        return locationMode.trim().toUpperCase(Locale.ROOT);
    }

    private void applyNoteDefaultsFromPhoto(Long noteId, Photo photo) {
        if (noteId == null || photo == null) {
            return;
        }
        MediaDefaultContext context = buildPhotoDefaultContext(photo);
        tripNoteService.applyDefaultAnchorAndLocation(
                noteId,
                context.captureTs(),
                context.lat(),
                context.lng(),
                context.locationName(),
                context.coordType(),
                "MEDIA_DEFAULT"
        );
    }

    private void applyNoteDefaultsFromVideo(Long noteId, Video video) {
        if (noteId == null || video == null) {
            return;
        }
        MediaDefaultContext context = buildVideoDefaultContext(video);
        tripNoteService.applyDefaultAnchorAndLocation(
                noteId,
                context.captureTs(),
                context.lat(),
                context.lng(),
                context.locationName(),
                context.coordType(),
                "MEDIA_DEFAULT"
        );
    }

    private MediaDefaultContext buildPhotoDefaultContext(Photo photo) {
        Long captureTs = photo.getCaptureTsOverride() != null
                ? photo.getCaptureTsOverride()
                : (photo.getShotTimeExif() != null ? photo.getShotTimeExif().getTime() : null);
        byte[] latBytes = photo.getCaptureLatOverride() != null ? photo.getCaptureLatOverride() : photo.getLatEnc();
        byte[] lngBytes = photo.getCaptureLngOverride() != null ? photo.getCaptureLngOverride() : photo.getLngEnc();
        return buildMediaDefaultContext(captureTs, latBytes, lngBytes, photo.getCaptureCoordType(), photo.getCaptureCoordSource(), photo.getLocationName());
    }

    private MediaDefaultContext buildVideoDefaultContext(Video video) {
        Long captureTs = video.getCaptureTsOverride() != null
                ? video.getCaptureTsOverride()
                : (video.getShotTimeExif() != null ? video.getShotTimeExif().getTime() : null);
        byte[] latBytes = video.getCaptureLatOverride() != null ? video.getCaptureLatOverride() : video.getLatEnc();
        byte[] lngBytes = video.getCaptureLngOverride() != null ? video.getCaptureLngOverride() : video.getLngEnc();
        return buildMediaDefaultContext(captureTs, latBytes, lngBytes, video.getCaptureCoordType(), video.getCaptureCoordSource(), video.getLocationName());
    }

    private MediaDefaultContext buildMediaDefaultContext(Long captureTs, byte[] latBytes, byte[] lngBytes, String coordType, String coordSource, String locationName) {
        Double lat = decodeDouble(latBytes);
        Double lng = decodeDouble(lngBytes);
        if (lat == null || lng == null || "NONE".equalsIgnoreCase(coordSource)) {
            return new MediaDefaultContext(captureTs, null, null, locationName, null);
        }
        String normalizedCoordType = coordType != null && !coordType.isBlank()
                ? coordType.trim().toUpperCase(Locale.ROOT)
                : ("EXIF".equalsIgnoreCase(coordSource) ? "WGS84" : "GCJ02");
        if ("WGS84".equals(normalizedCoordType)) {
            double[] converted = GeoUtils.wgs84ToGcj02(lat, lng);
            lat = converted[0];
            lng = converted[1];
            normalizedCoordType = "GCJ02";
        }
        return new MediaDefaultContext(captureTs, lat, lng, locationName, normalizedCoordType);
    }

    private Double decodeDouble(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        long bits = 0L;
        for (int i = 0; i < 8; i++) {
            bits |= ((long) bytes[i] & 0xFFL) << (i * 8);
        }
        return Double.longBitsToDouble(bits);
    }

    private Map<String, Object> toPhotoPayload(Photo photo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", photo.getId());
        payload.put("type", "photo");
        payload.put("tripId", photo.getTripId());
        payload.put("url", photo.getObjectKey());
        payload.put("thumbnailUrl", photo.getObjectKey());
        payload.put("caption", photo.getUserCaption());
        payload.put("locationName", photo.getLocationName());
        payload.put("shotTime", DateTimeUtils.formatDateTime(photo.getShotTimeExif()));
        payload.put("captureCoordType", photo.getCaptureCoordType());
        payload.put("captureCoordSource", photo.getCaptureCoordSource());
        payload.put("bindingStatus", photo.getBindingStatus());
        payload.put("isCover", photo.getIsCover());
        return payload;
    }

    private Map<String, Object> toVideoPayload(Video video) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", video.getId());
        payload.put("type", "video");
        payload.put("tripId", video.getTripId());
        payload.put("url", video.getObjectKey());
        payload.put("thumbnailUrl", video.getThumbnailObjectKey());
        payload.put("duration", video.getDurationSec());
        payload.put("durationSec", video.getDurationSec());
        payload.put("resolution", video.getResolution());
        payload.put("caption", video.getUserCaption());
        payload.put("locationName", video.getLocationName());
        payload.put("shotTime", DateTimeUtils.formatDateTime(video.getShotTimeExif()));
        payload.put("createdAt", DateTimeUtils.formatDateTime(video.getCreatedAt()));
        payload.put("captureCoordType", video.getCaptureCoordType());
        payload.put("captureCoordSource", video.getCaptureCoordSource());
        payload.put("bindingStatus", video.getBindingStatus());
        payload.put("processingStatus", video.getProcessingStatus() != null ? video.getProcessingStatus().name() : null);
        return payload;
    }

    private record MediaDefaultContext(Long captureTs, Double lat, Double lng, String locationName, String coordType) {}
}
