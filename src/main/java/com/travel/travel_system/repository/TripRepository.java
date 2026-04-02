package com.travel.travel_system.repository;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.enums.TripStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long>, JpaSpecificationExecutor<Trip> {

    /**
     * 根据用户 ID 查询行程列表（按创建时间倒序）
     */
    Page<Trip> findByUserId(Long userId, Pageable pageable);

    /**
     * 根据用户 ID 和状态查询行程列表（分页）
     */
    Page<Trip> findByUserIdAndStatus(Long userId, TripStatus status, Pageable pageable);

    /**
     * 根据用户 ID 查询指定状态的行程（列表）
     */
    List<Trip> findByUserIdAndStatus(Long userId, TripStatus status);

    /**
     * 查询用户最近的行程
     */
    Trip findFirstByUserIdOrderByStartTimeDesc(Long userId);

    /**
     * 查询用户最早的行程
     */
    Trip findFirstByUserIdOrderByStartTimeAsc(Long userId);

    /**
     * 根据标题模糊查询
     */
    @Query("SELECT t FROM Trip t WHERE t.userId = :userId AND t.title LIKE %:keyword%")
    Page<Trip> findByUserIdAndTitleContaining(
        @Param("userId") Long userId,
        @Param("keyword") String keyword,
        Pageable pageable
    );

    /**
     * 根据时间范围查询行程
     */
    @Query("SELECT t FROM Trip t WHERE t.userId = :userId AND t.startTime BETWEEN :startTime AND :endTime")
    Page<Trip> findByUserIdAndStartTimeBetween(
        @Param("userId") Long userId,
        @Param("startTime") Date startTime,
        @Param("endTime") Date endTime,
        Pageable pageable
    );

    /**
     * 根据隐私模式查询行程
     */
    List<Trip> findByUserIdAndPrivacyMode(Long userId, String privacyMode);

    /**
     * 统计用户行程数量
     */
    long countByUserId(Long userId);

    /**
     * 统计用户指定状态的行程数量
     */
    long countByUserIdAndStatus(Long userId, TripStatus status);

    /**
     * 统计用户总距离
     */
    @Query("SELECT SUM(t.distanceM) FROM Trip t WHERE t.userId = :userId")
    Long sumDistanceByUserId(@Param("userId") Long userId);

    /**
     * 统计用户总时长
     */
    @Query("SELECT SUM(t.durationSec) FROM Trip t WHERE t.userId = :userId")
    Long sumDurationByUserId(@Param("userId") Long userId);

    /**
     * 统计用户总照片数
     */
    @Query("SELECT SUM(t.photoCount) FROM Trip t WHERE t.userId = :userId")
    Integer sumPhotoCountByUserId(@Param("userId") Long userId);

    /**
     * 统计用户总视频数
     */
    @Query("SELECT SUM(t.videoCount) FROM Trip t WHERE t.userId = :userId")
    Integer sumVideoCountByUserId(@Param("userId") Long userId);

    /**
     * 查询已完成的行程（按结束时间倒序）
     */
    Page<Trip> findByUserIdAndStatusOrderByEndTimeDesc(Long userId, TripStatus status, Pageable pageable);
}
