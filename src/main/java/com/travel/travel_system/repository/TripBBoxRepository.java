package com.travel.travel_system.repository;

import com.travel.travel_system.model.TripBBox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripBBoxRepository extends JpaRepository<TripBBox, Long> {

    /**
     * 根据行程 ID 查询包围盒
     */
    Optional<TripBBox> findByTripId(Long tripId);

    /**
     * 删除指定行程的包围盒
     */
    void deleteByTripId(Long tripId);

    /**
     * 根据行程 ID 查询包围盒（不存在则创建）
     */
    default TripBBox findByTripIdOrCreate(Long tripId) {
        return findByTripId(tripId).orElseGet(() -> {
            TripBBox bbox = new TripBBox();
            bbox.setTripId(tripId);
            bbox.setMinLat(0.0f);
            bbox.setMaxLat(0.0f);
            bbox.setMinLng(0.0f);
            bbox.setMaxLng(0.0f);
            return bbox;
        });
    }
}
