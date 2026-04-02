package com.travel.travel_system.repository;

import com.travel.travel_system.model.TripAiSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripAiSummaryRepository extends JpaRepository<TripAiSummary, Long> {

    /**
     * 根据行程 ID 查询 AI 总结
     */
    Optional<TripAiSummary> findByTripId(Long tripId);

    /**
     * 根据用户 ID 和行程 ID 查询 AI 总结
     */
    Optional<TripAiSummary> findByUserIdAndTripId(Long userId, Long tripId);

    /**
     * 删除指定行程的 AI 总结
     */
    void deleteByTripId(Long tripId);

    /**
     * 删除指定用户的指定行程的 AI 总结
     */
    void deleteByUserIdAndTripId(Long userId, Long tripId);

    /**
     * 根据用户 ID 查询 AI 总结列表
     */
    java.util.List<TripAiSummary> findByUserIdOrderByGeneratedAtDesc(Long userId);

    /**
     * 根据模型名称查询 AI 总结
     */
    java.util.List<TripAiSummary> findByModelName(String modelName);

    /**
     * 根据版本查询 AI 总结
     */
    java.util.List<TripAiSummary> findByVersion(String version);
}
