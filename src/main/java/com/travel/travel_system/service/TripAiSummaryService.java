package com.travel.travel_system.service;

import com.travel.travel_system.model.TripAiSummary;

import java.util.List;
import java.util.Optional;

public interface TripAiSummaryService {

    TripAiSummary createSummary(TripAiSummary summary);

    Optional<TripAiSummary> getSummary(Long summaryId);

    Optional<TripAiSummary> getSummaryByTrip(Long tripId);

    Optional<TripAiSummary> getSummaryByUserAndTrip(Long userId, Long tripId);

    List<TripAiSummary> getSummariesByUser(Long userId);

    List<TripAiSummary> getSummariesByModel(String modelName);

    TripAiSummary updateSummary(Long summaryId, String overview, String highlights, String bestMoment, String routeSummary);

    TripAiSummary updateModelInfo(Long summaryId, String modelName, String version);

    void deleteSummary(Long summaryId);

    void deleteSummaryByTrip(Long tripId);

    void deleteSummaryByUserAndTrip(Long userId, Long tripId);
}
