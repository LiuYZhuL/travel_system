package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.VideoProcessingStatus;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.MediaAnchorProjectionService;
import com.travel.travel_system.service.VideoService;
import com.travel.travel_system.service.pub.OssService;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;

import java.io.IOException;
import java.util.Date;
import java.util.List;

@Service
public class VideoServiceImpl implements VideoService {

    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private AnchorRepository anchorRepository;
    @Autowired
    private OssService ossService;
    @Autowired
    private MediaAnchorProjectionService mediaAnchorProjectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public Video uploadVideo(Long tripId, MultipartFile file, String userCaption) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        String objectKey;
        try {
            objectKey = ossService.uploadFile(file, "videos/" + tripId);
        } catch (IOException e) {
            throw new RuntimeException("视频上传失败: " + e.getMessage(), e);
        }

        Video video = new Video();
        video.setUserId(trip.getUserId());
        video.setTripId(tripId);
        video.setObjectKey(objectKey);
        video.setFileHash(calculateHash(file));
        video.setFileSize(file.getSize());
        video.setCreatedAt(new Date());
        video.setUserCaption(userCaption);
        video.setPrivacyMode(PrivacyMode.PUBLIC);
        video.setProcessingStatus(VideoProcessingStatus.PENDING);
        video.setBindingStatus("PENDING");

        VideoMeta meta = readVideoMeta(file);
        if (meta != null) {
            video.setShotTimeExif(meta.shotTime);
            if (meta.lat != null && meta.lng != null) {
                video.setLatEnc(TrackPointServiceImpl.encodeDoubleStatic(meta.lat));
                video.setLngEnc(TrackPointServiceImpl.encodeDoubleStatic(meta.lng));
                video.setCaptureCoordSource("EXIF");
                video.setCaptureCoordType("WGS84");
            } else {
                video.setCaptureCoordSource("NONE");
                video.setCaptureCoordType(null);
            }
            video.setCaptureTimeSource(meta.shotTime != null ? "EXIF" : "NONE");
            video.setDurationSec(meta.durationSec);
            video.setResolution(meta.resolution);
        } else {
            // P0 修复：不要再把 uploadTime 当成拍摄时间
            video.setShotTimeExif(null);
            video.setCaptureTimeSource("NONE");
            video.setCaptureCoordSource("NONE");
            video.setCaptureCoordType(null);
        }

        Video savedVideo = videoRepository.save(video);
        refreshTripMediaCounts(tripId);

        // 统一走投影服务
        mediaAnchorProjectionService.projectVideoAnchor(savedVideo.getId(), tripId);

        return savedVideo;
    }

    @Override
    public Video getVideo(Long videoId) {
        return videoRepository.findById(videoId).orElse(null);
    }

    @Override
    public List<Video> getVideosByTrip(Long tripId) {
        return videoRepository.findByTripIdOrderByShotTimeExifAsc(tripId);
    }

    @Override
    @Transactional
    public Video updateVideoInfo(Long videoId, String userCaption, String privacyMode) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId: " + videoId));

        if (userCaption != null) {
            video.setUserCaption(userCaption);
        }
        if (privacyMode != null && !privacyMode.trim().isEmpty()) {
            try {
                video.setPrivacyMode(PrivacyMode.valueOf(privacyMode.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return videoRepository.save(video);
    }

    @Override
    @Transactional
    public void deleteVideo(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId: " + videoId));

        List<Anchor> anchors = anchorRepository.findByVideoId(videoId);
        for (Anchor anchor : anchors) {
            anchorRepository.delete(anchor);
        }

        if (video.getObjectKey() != null) {
            try {
                String objectName = extractObjectName(video.getObjectKey());
                ossService.deleteFile(objectName);
            } catch (Exception ignored) {
            }
        }
        if (video.getThumbnailObjectKey() != null) {
            try {
                String objectName = extractObjectName(video.getThumbnailObjectKey());
                ossService.deleteFile(objectName);
            } catch (Exception ignored) {
            }
        }

        videoRepository.delete(video);
        refreshTripMediaCounts(video.getTripId());
    }

    @Override
    public Video getVideoAnchor(Long videoId) {
        return videoRepository.findById(videoId).orElse(null);
    }

    @Override
    @Async("videoProcessExecutor")
    public void processVideoAsync(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId: " + videoId));

        video.setProcessingStatus(VideoProcessingStatus.PROCESSING);
        videoRepository.save(video);

        Path tempVideoFile = null;
        try {
            String suffix = ".mp4";
            if (video.getObjectKey() != null) {
                int dot = video.getObjectKey().lastIndexOf('.');
                if (dot >= 0 && dot < video.getObjectKey().length() - 1) {
                    suffix = video.getObjectKey().substring(dot);
                }
            }

            byte[] videoBytes = ossService.getFileBytes(extractObjectName(video.getObjectKey()));
            tempVideoFile = Files.createTempFile("video-process-", suffix);
            Files.write(tempVideoFile, videoBytes);

            generateAndUploadThumbnail(video, tempVideoFile.toFile());

            video.setProcessingStatus(VideoProcessingStatus.COMPLETED);

            mediaAnchorProjectionService.projectVideoAnchor(videoId, video.getTripId());
        } catch (Exception e) {
            video.setProcessingStatus(VideoProcessingStatus.FAILED);
        } finally {
            if (tempVideoFile != null) {
                try {
                    Files.deleteIfExists(tempVideoFile);
                } catch (Exception ignored) {
                }
            }
        }

        videoRepository.save(video);
    }


    @Override
    @Transactional
    public Video updateVideoAssistInfo(Long videoId, Long captureTsOverride, Double manualLat, Double manualLng, String coordType, String locationName, String locationMode) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId: " + videoId));

        video.setCaptureTsOverride(captureTsOverride);

        String normalizedMode = locationMode == null ? "" : locationMode.trim().toUpperCase();
        if (manualLat != null && manualLng != null && !"EXIF".equals(normalizedMode)) {
            video.setCaptureLatOverride(TrackPointServiceImpl.encodeDoubleStatic(manualLat));
            video.setCaptureLngOverride(TrackPointServiceImpl.encodeDoubleStatic(manualLng));
            video.setCaptureCoordSource("MANUAL");
            video.setCaptureCoordType(coordType != null && !coordType.isBlank() ? coordType.toUpperCase() : "GCJ02");
        } else if ("NONE".equals(normalizedMode)) {
            video.setCaptureLatOverride(null);
            video.setCaptureLngOverride(null);
            video.setCaptureCoordSource("NONE");
            video.setCaptureCoordType(null);
        } else if ("EXIF".equals(normalizedMode)) {
            video.setCaptureLatOverride(null);
            video.setCaptureLngOverride(null);
            if (video.getLatEnc() != null && video.getLngEnc() != null) {
                video.setCaptureCoordSource("EXIF");
                video.setCaptureCoordType("WGS84");
            } else {
                video.setCaptureCoordSource("NONE");
                video.setCaptureCoordType(null);
            }
        } else {
            video.setCaptureLatOverride(null);
            video.setCaptureLngOverride(null);
            if (captureTsOverride != null) {
                video.setCaptureCoordSource("NONE");
                video.setCaptureCoordType(null);
            }
        }

        if (captureTsOverride != null) {
            video.setCaptureTimeSource("USER_INPUT");
        }
        if (locationName != null) {
            video.setLocationName(locationName.isBlank() ? null : locationName.trim());
        }

        Video saved = videoRepository.save(video);

        mediaAnchorProjectionService.projectVideoAnchor(saved.getId(), saved.getTripId());
        return saved;
    }

    private VideoMeta readVideoMeta(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        Path tempFile = null;
        try {
            String suffix = ".tmp";
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                int dot = originalName.lastIndexOf('.');
                if (dot >= 0 && dot < originalName.length() - 1) {
                    suffix = originalName.substring(dot);
                }
            }

            tempFile = Files.createTempFile("video-meta-", suffix);
            file.transferTo(tempFile.toFile());

            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe",
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_entries",
                    "format=duration:format_tags=creation_time,location,location-eng,com.apple.quicktime.location.ISO6709:"
                            + "stream=codec_type,width,height:stream_tags=creation_time,location,location-eng,com.apple.quicktime.location.ISO6709",
                    tempFile.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String json;
            try (InputStream in = process.getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0 || json == null || json.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(json);
            VideoMeta meta = new VideoMeta();

            JsonNode formatNode = root.path("format");
            JsonNode streamsNode = root.path("streams");

            // 1. 时长
            if (formatNode.hasNonNull("duration")) {
                try {
                    double seconds = Double.parseDouble(formatNode.get("duration").asText());
                    meta.durationSec = (int) Math.round(seconds);
                } catch (Exception ignored) {
                }
            }

            // 2. 分辨率：优先找 video stream
            if (streamsNode.isArray()) {
                for (JsonNode stream : streamsNode) {
                    if ("video".equalsIgnoreCase(stream.path("codec_type").asText())) {
                        int width = stream.path("width").asInt(0);
                        int height = stream.path("height").asInt(0);
                        if (width > 0 && height > 0) {
                            meta.resolution = width + "x" + height;
                        }

                        // stream 级 creation_time 优先
                        Date streamCreation = parseCreationTimeFromTags(stream.path("tags"));
                        if (streamCreation != null) {
                            meta.shotTime = streamCreation;
                        }

                        // stream 级位置标签
                        double[] streamLocation = parseLocationFromTags(stream.path("tags"));
                        if (streamLocation != null) {
                            meta.lat = streamLocation[0];
                            meta.lng = streamLocation[1];
                        }
                        break;
                    }
                }
            }

            // 3. format 级 creation_time 兜底
            if (meta.shotTime == null) {
                Date formatCreation = parseCreationTimeFromTags(formatNode.path("tags"));
                if (formatCreation != null) {
                    meta.shotTime = formatCreation;
                }
            }

            // 4. format 级位置兜底
            if (meta.lat == null || meta.lng == null) {
                double[] formatLocation = parseLocationFromTags(formatNode.path("tags"));
                if (formatLocation != null) {
                    meta.lat = formatLocation[0];
                    meta.lng = formatLocation[1];
                }
            }

            if (meta.shotTime == null && meta.lat == null && meta.lng == null
                    && meta.durationSec == null && meta.resolution == null) {
                return null;
            }
            return meta;
        } catch (Exception e) {
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void generateAndUploadThumbnail(Video video, File videoFile) {
        FFmpegFrameGrabber grabber = null;
        try {
            grabber = new FFmpegFrameGrabber(videoFile);
            grabber.start();

            if (video.getDurationSec() == null || video.getDurationSec() <= 0) {
                long lengthInTime = grabber.getLengthInTime();
                if (lengthInTime > 0) {
                    video.setDurationSec((int) Math.max(1L, Math.round(lengthInTime / 1_000_000d)));
                }
            }

            int totalFrames = grabber.getLengthInFrames();
            int targetFrame = Math.max(totalFrames / 3, 1);

            Frame frame = null;
            for (int i = 0; i <= targetFrame; i++) {
                frame = grabber.grabImage();
                if (frame == null) {
                    break;
                }
            }

            if (frame != null) {
                Java2DFrameConverter converter = new Java2DFrameConverter();
                BufferedImage bufferedImage = converter.convert(frame);

                if (bufferedImage != null) {
                    int width = bufferedImage.getWidth();
                    int height = bufferedImage.getHeight();
                    if ((video.getResolution() == null || video.getResolution().isBlank()) && width > 0 && height > 0) {
                        video.setResolution(width + "x" + height);
                    }

                    int thumbWidth = 320;
                    int thumbHeight = (int) ((double) height / width * thumbWidth);

                    BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
                    thumbnail.getGraphics().drawImage(bufferedImage.getScaledInstance(thumbWidth, thumbHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(thumbnail, "jpg", baos);
                    byte[] thumbnailBytes = baos.toByteArray();

                    String thumbnailObjectKey = ossService.generateFileName("thumbnail.jpg", "thumbnails/" + video.getTripId());
                    String thumbnailUrl = ossService.uploadFile(thumbnailBytes, thumbnailObjectKey);

                    video.setThumbnailObjectKey(thumbnailUrl);
                }
            }

            grabber.stop();
        } catch (Exception e) {
            throw new RuntimeException("生成视频缩略图失败: " + e.getMessage(), e);
        } finally {
            if (grabber != null) {
                try {
                    grabber.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Date parseCreationTimeFromTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }

        String creationTime = textOrNull(tagsNode, "creation_time");
        if (creationTime == null || creationTime.isBlank()) {
            return null;
        }

        try {
            return Date.from(Instant.parse(creationTime));
        } catch (Exception ignored) {
        }

        try {
            return Date.from(OffsetDateTime.parse(creationTime).toInstant());
        } catch (Exception ignored) {
        }

        return null;
    }

    private double[] parseLocationFromTags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }

        String raw = firstNonBlank(
                textOrNull(tagsNode, "com.apple.quicktime.location.ISO6709"),
                textOrNull(tagsNode, "location"),
                textOrNull(tagsNode, "location-eng")
        );
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return parseIso6709(raw);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        String text = child.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 解析类似：
     * +39.0298+125.7540/
     * +22.5431+114.0579+015.000/
     */
    private double[] parseIso6709(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        // 找第二个符号位
        int secondSign = -1;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || c == '-') {
                secondSign = i;
                break;
            }
        }
        if (secondSign < 0) {
            return null;
        }

        try {
            String latStr = s.substring(0, secondSign);
            String remain = s.substring(secondSign);

            int thirdSign = -1;
            for (int i = 1; i < remain.length(); i++) {
                char c = remain.charAt(i);
                if (c == '+' || c == '-') {
                    thirdSign = i;
                    break;
                }
            }

            String lngStr = thirdSign < 0 ? remain : remain.substring(0, thirdSign);

            double lat = Double.parseDouble(latStr);
            double lng = Double.parseDouble(lngStr);

            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                return null;
            }
            return new double[]{lat, lng};
        } catch (Exception e) {
            return null;
        }
    }

    private String calculateHash(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractObjectName(String url) {
        if (url == null) return null;
        int idx = url.indexOf(".com/");
        if (idx > 0) {
            return url.substring(idx + 5);
        }
        return url;
    }

    private void refreshTripMediaCounts(Long tripId) {
        if (tripId == null) {
            return;
        }
        tripRepository.findById(tripId).ifPresent(trip -> {
            trip.setPhotoCount((int) photoRepository.countByTripId(tripId));
            trip.setVideoCount((int) videoRepository.countByTripId(tripId));
            tripRepository.save(trip);
        });
    }

    private static class VideoMeta {
        Date shotTime;
        Double lat;
        Double lng;
        Integer durationSec;
        String resolution;
    }
}
