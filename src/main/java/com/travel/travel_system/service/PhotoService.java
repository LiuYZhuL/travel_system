package com.travel.travel_system.service;

import com.travel.travel_system.model.Photo;

import java.util.List;

public interface PhotoService {
    /**
     * 上传照片并提取 Exif 信息
     * @param tripId 行程 ID
     * @param fileName 文件名
     * @param contentType 文件类型
     * @param fileBase64 文件的 Base64 编码
     * @return 上传后的照片信息
     */
    Photo uploadPhoto(Long tripId, String fileName, String contentType, String fileBase64);

    /**
     * 获取指定照片的锚点
     * @param photoId 照片 ID
     * @return 照片的锚点数据（时间、位置、可信度等）
     */
    Photo getPhotoAnchor(Long photoId);

    /**
     * 获取指定行程的所有照片
     * @param tripId 行程 ID
     * @return 照片列表
     */
    List<Photo> getPhotosByTrip(Long tripId);

    Photo updatePhotoInfo(Long photoId, String userCaption, String privacyMode);

    void deletePhoto(Long photoId);
}
