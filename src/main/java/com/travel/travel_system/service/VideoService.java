package com.travel.travel_system.service;

import com.travel.travel_system.model.Video;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VideoService {

    Video uploadVideo(Long tripId, MultipartFile file, String userCaption);

    Video getVideo(Long videoId);

    List<Video> getVideosByTrip(Long tripId);

    Video updateVideoInfo(Long videoId, String userCaption, String privacyMode);

    void deleteVideo(Long videoId);

    Video getVideoAnchor(Long videoId);

    void processVideoAsync(Long videoId);
}
