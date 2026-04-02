package com.travel.travel_system.repository;

import com.travel.travel_system.model.StoryBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoryBlockRepository extends JpaRepository<StoryBlock, Long> {

    /**
     * 根据行程 ID 查询故事块列表（按排序时间和索引排序）
     */
    List<StoryBlock> findByTripIdOrderBySortTimeAscSortIndexAsc(Long tripId);

    /**
     * 根据用户 ID 和行程 ID 查询故事块列表
     */
    List<StoryBlock> findByUserIdAndTripIdOrderBySortTimeAscSortIndexAsc(Long userId, Long tripId);

    /**
     * 根据行程 ID 和块类型查询故事块列表
     */
    List<StoryBlock> findByTripIdAndBlockTypeOrderBySortTimeAscSortIndexAsc(Long tripId, String blockType);

    /**
     * 根据引用对象类型和引用对象 ID 查询故事块
     */
    List<StoryBlock> findByRefTypeAndRefIdOrderBySortTimeAscSortIndexAsc(String refType, Long refId);

    /**
     * 根据行程 ID 查询故事块数量
     */
    long countByTripId(Long tripId);

    /**
     * 根据用户 ID 查询故事块数量
     */
    long countByUserId(Long userId);

    /**
     * 删除指定行程的故事块
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 分页查询故事块
     */
    Page<StoryBlock> findByTripId(Long tripId, Pageable pageable);

    /**
     * 查询隐藏的故事块
     */
    List<StoryBlock> findByTripIdAndIsHiddenTrue(Long tripId);

    /**
     * 查询未隐藏的故事块
     */
    List<StoryBlock> findByTripIdAndIsHiddenFalse(Long tripId);

    /**
     * 根据块类型查询故事块
     */
    List<StoryBlock> findByUserIdAndBlockTypeOrderBySortTimeDesc(Long userId, String blockType);

    /**
     * 根据封面查询故事块
     */
    List<StoryBlock> findByTripIdAndCoverObjectKeyIsNotNull(Long tripId);

    /**
     * 查询特定类型的故事块（支持多个类型）
     */
    @Query("SELECT s FROM StoryBlock s WHERE s.tripId = :tripId AND s.blockType IN :blockTypes ORDER BY s.sortTime ASC, s.sortIndex ASC")
    List<StoryBlock> findByTripIdAndBlockTypeIn(
        @Param("tripId") Long tripId,
        @Param("blockTypes") List<String> blockTypes
    );

    /**
     * 查询故事块的第一个块
     */
    StoryBlock findFirstByTripIdOrderBySortTimeAscSortIndexAsc(Long tripId);

    /**
     * 查询故事块的最后一个块
     */
    StoryBlock findFirstByTripIdOrderBySortTimeDescSortIndexDesc(Long tripId);
}
