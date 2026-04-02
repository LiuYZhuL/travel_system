package com.travel.travel_system.repository;

import com.travel.travel_system.model.Anchor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnchorRepository extends JpaRepository<Anchor, Long> {

    /**
     * 根据行程 ID 查询锚点列表
     */
    List<Anchor> findByTripId(Long tripId);

    /**
     * 根据行程 ID 查询锚点列表（按匹配时间排序）
     */
    List<Anchor> findByTripIdOrderByMatchedTsAsc(Long tripId);

    /**
     * 根据照片 ID 查询锚点
     */
    List<Anchor> findByPhotoId(Long photoId);

    /**
     * 根据视频 ID 查询锚点
     */
    List<Anchor> findByVideoId(Long videoId);

    /**
     * 根据行程 ID 查询锚点数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询锚点数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的锚点
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询锚点
     */
    Page<Anchor> findByTripId(Long tripId, Pageable pageable);

    /**
     * 根据匹配置信度查询锚点
     */
    List<Anchor> findByTripIdAndConfidenceGreaterThanEqual(Long tripId, Float confidence);

    /**
     * 根据匹配方式查询锚点
     */
    List<Anchor> findByTripIdAndMatchMethod(Long tripId, String matchMethod);

    /**
     * 查询人工修正的锚点
     */
    List<Anchor> findByTripIdAndManualOverrideTrue(Long tripId);

    /**
     * 根据媒体 ID 和类型查询锚点
     */
    @Query("SELECT a FROM Anchor a WHERE a.tripId = :tripId AND ((a.photoId = :mediaId AND :type = 'PHOTO') OR (a.videoId = :mediaId AND :type = 'VIDEO'))")
    List<Anchor> findByTripIdAndMediaId(
        @Param("tripId") Long tripId,
        @Param("mediaId") Long mediaId,
        @Param("type") String type
    );
}
