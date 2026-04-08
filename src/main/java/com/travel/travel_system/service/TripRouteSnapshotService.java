package com.travel.travel_system.service;

import com.travel.travel_system.dto.TripRouteSnapshotPayload;
import com.travel.travel_system.model.TripRouteSnapshot;

import java.util.Optional;

public interface TripRouteSnapshotService {
    TripRouteSnapshot finalizeFinishedTrip(Long tripId);
    Optional<TripRouteSnapshot> getSnapshotMeta(Long tripId);
    Optional<TripRouteSnapshotPayload> loadSnapshotPayload(Long tripId);
    Optional<TripRouteSnapshotPayload> loadSnapshotPayloadAndWarmRedis(Long tripId);
    TripRouteSnapshot saveLatestSnapshot(Long tripId);
}
