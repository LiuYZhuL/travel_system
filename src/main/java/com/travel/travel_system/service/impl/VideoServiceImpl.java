package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.model.enums.MatchMethod;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.VideoProcessingStatus;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.VideoService;
import com.travel.travel_system.service.pub.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private AnchorRepository anchorRepository;

    @Autowired
    private OssService ossService;

    @Override
    @Transactional
    public Video uploadVideo(Long tripId, MultipartFile file, String userCaption) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        String objectKey;
        try {
            objectKey = ossService.uploadFile(file, "videos/" + tripId);
        } catch (IOException e) {
            throw new RuntimeException("视频上传失败: " + e.getMessage());
        }

        Video video = new Video();
        video.setUserId(trip.getUserId());
        video.setTripId(tripId);
        video.setObjectKey(objectKey);
        video.setFileHash(calculateHash(file));
        video.setFileSize(file.getSize());
        video.setShotTimeExif(new Date());
        video.setCreatedAt(new Date());
        video.setUserCaption(userCaption);
        video.setPrivacyMode(PrivacyMode.PUBLIC);
        video.setProcessingStatus(VideoProcessingStatus.PENDING);

        Video savedVideo = videoRepository.save(video);

        Anchor anchor = new Anchor();
        anchor.setUserId(trip.getUserId());
        anchor.setTripId(tripId);
        anchor.setVideoId(savedVideo.getId());
        anchor.setMatchedTs(System.currentTimeMillis());
        anchor.setMatchMethod(MatchMethod.MANUAL_PICK);
        anchor.setConfidence(1.0f);
        anchor.setManualOverride(false);
        anchor.setCreatedAt(new Date());
        anchorRepository.save(anchor);

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
    }

    @Override
    public Video getVideoAnchor(Long videoId) {
        return videoRepository.findById(videoId).orElse(null);
    }

    @Override
    @Transactional
    public void processVideoAsync(Long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("视频不存在，videoId: " + videoId));

        video.setProcessingStatus(VideoProcessingStatus.PROCESSING);
        videoRepository.save(video);

        try {
            Thread.sleep(1000);
            video.setProcessingStatus(VideoProcessingStatus.COMPLETED);
        } catch (Exception e) {
            video.setProcessingStatus(VideoProcessingStatus.FAILED);
        }

        videoRepository.save(video);
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
}
