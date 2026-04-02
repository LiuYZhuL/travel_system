package com.travel.travel_system.repository;

import com.travel.travel_system.model.PlaceSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaceSummaryRepository extends JpaRepository<PlaceSummary, Long> {

    /**
     * 根据行程 ID 查询地点摘要列表
     */
    List<PlaceSummary> findByTripId(Long tripId);

    /**
     * 根据行程 ID 查询地点摘要列表（按开始时间排序）
     */
    List<PlaceSummary> findByTripIdOrderByStartTimeAsc(Long tripId);

    /**
     * 根据行程 ID 查询地点摘要列表（按停留时长排序）
     */
    List<PlaceSummary> findByTripIdOrderByDurationSecDesc(Long tripId);

    /**
     * 根据用户 ID 查询地点摘要列表
     */
    List<PlaceSummary> findByUserIdOrderByGeneratedAtDesc(Long userId);

    /**
     * 根据行程 ID 查询地点摘要数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询地点摘要数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的地点摘要
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询地点摘要
     */
    Page<PlaceSummary> findByTripId(Long tripId, Pageable pageable);

    /**
     * 根据城市查询地点摘要
     */
    List<PlaceSummary> findByTripIdAndCity(Long tripId, String city);

    /**
     * 根据区县查询地点摘要
     */
    List<PlaceSummary> findByTripIdAndDistrict(Long tripId, String district);

    /**
     * 根据隐私级别查询地点摘要
     */
    List<PlaceSummary> findByTripIdAndPrivacyLevel(Long tripId, String privacyLevel);

    /**
     * 查询有封面照片的地点摘要
     */
    List<PlaceSummary> findByTripIdAndPhotoCoverIdIsNotNull(Long tripId);

    /**
     * 查询停留时间超过指定时长的地点
     */
    List<PlaceSummary> findByTripIdAndDurationSecGreaterThanEqual(Long tripId, Long durationSec);

    /**
     * 根据 POI 名称模糊查询
     */
    @Query("SELECT p FROM PlaceSummary p WHERE p.tripId = :tripId AND p.poiName LIKE %:keyword%")
    List<PlaceSummary> findByTripIdAndPoiNameContaining(
        @Param("tripId") Long tripId,
        @Param("keyword") String keyword
    );

    /**
     * 根据地理范围查询地点摘要
     */
    @Query("SELECT p FROM PlaceSummary p WHERE p.tripId = :tripId AND p.centerLatEnc BETWEEN :minLat AND :maxLat AND p.centerLngEnc BETWEEN :minLng AND :maxLng")
    List<PlaceSummary> findByTripIdAndGeoBounds(
        @Param("tripId") Long tripId,
        @Param("minLat") byte[] minLat,
        @Param("maxLat") byte[] maxLat,
        @Param("minLng") byte[] minLng,
        @Param("maxLng") byte[] maxLng
    );
}
