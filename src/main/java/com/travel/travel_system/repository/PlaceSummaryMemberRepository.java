package com.travel.travel_system.repository;

import com.travel.travel_system.model.PlaceSummaryMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaceSummaryMemberRepository extends JpaRepository<PlaceSummaryMember, Long> {

    List<PlaceSummaryMember> findByPlaceSummaryIdOrderBySortIndexAscIdAsc(Long placeSummaryId);

    List<PlaceSummaryMember> findByTripIdOrderByPlaceSummaryIdAscSortIndexAscIdAsc(Long tripId);

    void deleteByTripId(Long tripId);

    void deleteByPlaceSummaryId(Long placeSummaryId);
}
