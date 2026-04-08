package com.travel.travel_system.service;

import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.enums.PrivacyMode;

import java.util.List;
import java.util.Optional;

public interface PlaceSummaryService {

    PlaceSummary createPlaceSummary(PlaceSummary placeSummary);

    Optional<PlaceSummary> getPlaceSummary(Long placeId);

    List<PlaceSummary> getPlaceSummariesByTrip(Long tripId);

    List<PlaceSummary> getPlaceSummariesByTripOrderByDuration(Long tripId);

    List<PlaceSummary> getPlaceSummariesByCity(Long tripId, String city);

    List<PlaceSummary> getPlaceSummariesByDistrict(Long tripId, String district);

    List<PlaceSummary> getPlaceSummariesWithCover(Long tripId);

    List<PlaceSummary> getLongStayPlaces(Long tripId, Long minDurationSec);

    List<PlaceSummary> searchByPoiName(Long tripId, String keyword);

    PlaceSummary updatePlaceSummary(Long placeId, String poiName, String userNotes, String userTags, PrivacyMode privacyLevel);

    PlaceSummary updateCover(Long placeId, Long photoCoverId, Long videoCoverId);

    PlaceSummary updateMediaCount(Long placeId, Integer photoCount, Integer videoCount);

    void deletePlaceSummary(Long placeId);

    void deletePlaceSummariesByTrip(Long tripId);

    long countByTrip(Long tripId);
}
