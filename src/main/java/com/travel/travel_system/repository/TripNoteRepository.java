package com.travel.travel_system.repository;

import com.travel.travel_system.model.TripNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripNoteRepository extends JpaRepository<TripNote, Long> {

    /**
     * 根据行程 ID 查询笔记列表
     */
    List<TripNote> findByTripIdOrderByCreatedAtDesc(Long tripId);

    /**
     * 根据用户 ID 和行程 ID 查询笔记列表
     */
    List<TripNote> findByUserIdAndTripIdOrderByCreatedAtDesc(Long userId, Long tripId);

    /**
     * 根据用户 ID 查询笔记列表
     */
    List<TripNote> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 根据行程 ID 查询笔记数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询笔记数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的笔记
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询笔记
     */
    Page<TripNote> findByTripId(Long tripId, Pageable pageable);

    /**
     * 根据隐私模式查询笔记
     */
    List<TripNote> findByTripIdAndPrivacyMode(Long tripId, String privacyMode);

    /**
     * 根据标题模糊查询
     */
    @Query("SELECT n FROM TripNote n WHERE n.tripId = :tripId AND n.title LIKE %:keyword%")
    List<TripNote> findByTripIdAndTitleContaining(
        @Param("tripId") Long tripId,
        @Param("keyword") String keyword
    );

    /**
     * 根据内容模糊查询
     */
    @Query("SELECT n FROM TripNote n WHERE n.tripId = :tripId AND n.content LIKE %:keyword%")
    List<TripNote> findByTripIdAndContentContaining(
        @Param("tripId") Long tripId,
        @Param("keyword") String keyword
    );

    /**
     * 查询有关联时间的笔记
     */
    List<TripNote> findByTripIdAndAnchorTsIsNotNull(Long tripId);

    /**
     * 查询有关联位置的笔记
     */
    List<TripNote> findByTripIdAndLatEncIsNotNull(Long tripId);
}
