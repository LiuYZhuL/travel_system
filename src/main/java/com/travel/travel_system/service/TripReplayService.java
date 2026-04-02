package com.travel.travel_system.service;

import com.travel.travel_system.dto.TripReplay;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.Video;

import java.util.List;

public interface TripReplayService {
    /**
     * 获取行程的回放数据
     * @param tripId 行程 ID
     * @return 行程轨迹点和照片的回放数据
     */
    TripReplay getTripReplay(Long tripId);

    /**
     * 获取指定行程的轨迹点与照片的时空对齐数据
     * @param tripId 行程 ID
     * @return 行程的轨迹点和照片列表
     */
    List<TrackPoint> getTrackPointsForReplay(Long tripId);

    /**
     * 获取行程回放时的照片
     * @param tripId 行程 ID
     * @return 照片列表
     */
    // ... existing code ...
    List<Photo> getPhotosForReplay(Long tripId);

    List<Video> getVideosForReplay(Long tripId);

}
