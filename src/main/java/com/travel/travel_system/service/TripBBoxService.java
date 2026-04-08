package com.travel.travel_system.service;

import com.travel.travel_system.model.TripBBox;

import java.util.Optional;

public interface TripBBoxService {

    TripBBox createBBox(TripBBox bbox);

    Optional<TripBBox> getBBox(Long tripId);

    TripBBox getOrCreateBBox(Long tripId);

    TripBBox updateBBox(Long tripId, Float minLat, Float minLng, Float maxLat, Float maxLng);

    TripBBox expandBBox(Long tripId, Float lat, Float lng);

    void deleteBBox(Long tripId);

    boolean isValidBBox(TripBBox bbox);

    double[] getCenter(TripBBox bbox);

    double getDiagonalDistance(TripBBox bbox);
}
