package com.travel.travel_system.repository;

import com.travel.travel_system.model.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    /**
     * 根据行程 ID 查询视频列表
     */
    List<Video> findByTripId(Long tripId);

    /**
     * 根据行程 ID 查询视频列表（按拍摄时间排序）
     */
    List<Video> findByTripIdOrderByShotTimeExifAsc(Long tripId);

    /**
     * 根据用户 ID 查询视频列表（按创建时间倒序）
     */
    List<Video> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 根据文件哈希查询视频（用于去重）
     */
    Video findByFileHash(String fileHash);

    /**
     * 根据行程 ID 查询视频数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询视频数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的视频
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询视频
     */
    Page<Video> findByTripId(Long tripId, Pageable pageable);

    /**
     * 查询指定时间范围内的视频
     */
    @Query("SELECT v FROM Video v WHERE v.userId = :userId AND v.createdAt BETWEEN :startTime AND :endTime")
    List<Video> findByUserIdAndTimeRange(
        @Param("userId") Long userId,
        @Param("startTime") Date startTime,
        @Param("endTime") Date endTime
    );

    /**
     * 根据处理状态查询视频
     */
    List<Video> findByTripIdAndProcessingStatus(Long tripId, String processingStatus);

    /**
     * 根据隐私模式查询视频
     */
    List<Video> findByTripIdAndPrivacyMode(Long tripId, String privacyMode);

    /**
     * 查询最近的视频
     */
    Video findFirstByTripIdOrderByCreatedAtDesc(Long tripId);
}
