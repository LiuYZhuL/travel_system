package com.travel.travel_system.repository;

import com.travel.travel_system.model.TripSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripSegmentRepository extends JpaRepository<TripSegment, Long> {

    /**
     * 按分段序号升序查询某个行程的全部分段
     */
    List<TripSegment> findByTripIdOrderBySegmentNoAsc(Long tripId);

    /**
     * 查询某个行程当前仍未闭合的最新分段
     */
    Optional<TripSegment> findTopByTripIdAndIsClosedFalseOrderBySegmentNoDesc(Long tripId);

    /**
     * 查询某个行程分段号最大的那一段
     */
    Optional<TripSegment> findTopByTripIdOrderBySegmentNoDesc(Long tripId);

    /**
     * 查询某个行程下指定闭合状态的全部分段
     */
    List<TripSegment> findByTripIdAndIsClosedOrderBySegmentNoAsc(Long tripId, Boolean isClosed);

    /**
     * 删除某个行程下的全部分段
     */
    void deleteByTripId(Long tripId);
}