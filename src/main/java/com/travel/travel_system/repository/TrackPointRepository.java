package com.travel.travel_system.repository;

import com.travel.travel_system.model.TrackPoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    /**
     * 根据行程 ID 查询轨迹点列表
     */
    List<TrackPoint> findByTripId(Long tripId);

    /**
     * 根据行程 ID 查询轨迹点列表（按时间升序）
     */
    List<TrackPoint> findByTripIdOrderByTsAsc(Long tripId);

    /**
     * 根据行程 ID 和时间范围查询轨迹点
     */
    List<TrackPoint> findByTripIdAndTsBetween(Long tripId, Long startTime, Long endTime);

    /**
     * 根据行程 ID 查询轨迹点数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询轨迹点数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的轨迹点
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询轨迹点
     */
    Page<TrackPoint> findByTripId(Long tripId, Pageable pageable);

    /**
     * 查询指定时间范围内的轨迹点
     */
    @Query("SELECT t FROM TrackPoint t WHERE t.userId = :userId AND t.ts BETWEEN :startTime AND :endTime")
    List<TrackPoint> findByUserIdAndTimeRange(
        @Param("userId") Long userId,
        @Param("startTime") Long startTime,
        @Param("endTime") Long endTime
    );

    /**
     * 根据来源查询轨迹点
     */
    List<TrackPoint> findByTripIdAndSource(Long tripId, String source);

    /**
     * 查询最新的轨迹点
     */
    TrackPoint findFirstByTripIdOrderByTsDesc(Long tripId);

    /**
     * 查询最早的轨迹点
     */
    TrackPoint findFirstByTripIdOrderByTsAsc(Long tripId);
}
