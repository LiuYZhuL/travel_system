package com.travel.travel_system.service;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.vo.PlaceSummaryVO;
import com.travel.travel_system.vo.StoryBlockVO;
import com.travel.travel_system.vo.TripAISummaryVO;
import com.travel.travel_system.vo.TripDetailVO;
import com.travel.travel_system.vo.TripMapVO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TripService {
    Trip createTrip(Long userId, String title, String timezone, String privacyMode, String startTime);

    Optional<Trip> getTrip(Long tripId);

    Trip getUserTripOrThrow(Long userId, Long tripId);

    Page<Trip> getUserTripsPage(Long userId, Pageable pageable, String status);

    Page<Trip> searchUserTrips(Long userId, Pageable pageable, String keyword, String status, String startDate, String endDate);

    Trip updateTripBasic(Long userId, Long tripId, String title, String privacyMode);

    void updateTripPrivacy(Long tripId, String privacyMode);

    void deleteTrip(Long tripId);

    Trip finishTrip(Long tripId);

    Trip pauseTrip(Long tripId);

    Trip resumeTrip(Long tripId);

    Trip settleTrip(Long tripId);

    Map<String, Object> getTripStatistics(Long tripId);

    Map<String, Object> getTripStory(Long tripId);

    TripDetailVO getTripDetail(Long userId, Long tripId);

    TripMapVO getTripMap(Long userId, Long tripId);

    List<PlaceSummaryVO> getTripPlaces(Long userId, Long tripId);

    Map<String, Object> getTripStoryBlocks(Long userId, Long tripId, Integer pageNo, Integer pageSize);

    TripAISummaryVO getTripAiSummary(Long userId, Long tripId, boolean regenerate);

    List<StoryBlockVO> rebuildTripStoryBlocks(Long userId, Long tripId);

    Map<String, Object> getActiveTrip(Long userId);

    Integer uploadTrackPoints(Long userId, Long tripId, List<Map<String, Object>> points);

    Map<String, Object> getTrackStatus(Long userId, Long tripId);
}
