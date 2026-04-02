package com.travel.travel_system.repository;

import com.travel.travel_system.model.Photo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /**
     * 根据行程 ID 查询照片列表
     */
    List<Photo> findByTripId(Long tripId);

    /**
     * 根据行程 ID 查询照片列表（按拍摄时间排序）
     */
    List<Photo> findByTripIdOrderByShotTimeExifAsc(Long tripId);

    /**
     * 根据用户 ID 查询照片列表（按创建时间倒序）
     */
    List<Photo> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 根据文件哈希查询照片（用于去重）
     */
    Photo findByFileHash(String fileHash);

    /**
     * 根据行程 ID 查询照片数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询照片数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的照片
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询照片
     */
    Page<Photo> findByTripId(Long tripId, Pageable pageable);

    /**
     * 查询指定时间范围内的照片
     */
    @Query("SELECT p FROM Photo p WHERE p.userId = :userId AND p.createdAt BETWEEN :startTime AND :endTime")
    List<Photo> findByUserIdAndTimeRange(
        @Param("userId") Long userId,
        @Param("startTime") Date startTime,
        @Param("endTime") Date endTime
    );

    /**
     * 查询封面照片
     */
    List<Photo> findByTripIdAndIsCoverTrue(Long tripId);

    /**
     * 根据隐私模式查询照片
     */
    List<Photo> findByTripIdAndPrivacyMode(Long tripId, String privacyMode);

    /**
     * 查询最近的图片
     */
    Photo findFirstByTripIdOrderByCreatedAtDesc(Long tripId);
}
