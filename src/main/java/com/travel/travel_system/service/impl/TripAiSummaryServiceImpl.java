package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.TripAiSummary;
import com.travel.travel_system.repository.TripAiSummaryRepository;
import com.travel.travel_system.service.TripAiSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class TripAiSummaryServiceImpl implements TripAiSummaryService {

    @Autowired
    private TripAiSummaryRepository tripAiSummaryRepository;

    @Override
    @Transactional
    public TripAiSummary createSummary(TripAiSummary summary) {
        if (summary.getCreatedAt() == null) {
            summary.setCreatedAt(new Date());
        }
        if (summary.getGeneratedAt() == null) {
            summary.setGeneratedAt(new Date());
        }
        return tripAiSummaryRepository.save(summary);
    }

    @Override
    public Optional<TripAiSummary> getSummary(Long summaryId) {
        return tripAiSummaryRepository.findById(summaryId);
    }

    @Override
    public Optional<TripAiSummary> getSummaryByTrip(Long tripId) {
        return tripAiSummaryRepository.findByTripId(tripId);
    }

    @Override
    public Optional<TripAiSummary> getSummaryByUserAndTrip(Long userId, Long tripId) {
        return tripAiSummaryRepository.findByUserIdAndTripId(userId, tripId);
    }

    @Override
    public List<TripAiSummary> getSummariesByUser(Long userId) {
        return tripAiSummaryRepository.findByUserIdOrderByGeneratedAtDesc(userId);
    }

    @Override
    public List<TripAiSummary> getSummariesByModel(String modelName) {
        return tripAiSummaryRepository.findByModelName(modelName);
    }

    @Override
    @Transactional
    public TripAiSummary updateSummary(Long summaryId, String overview, String highlights, String bestMoment, String routeSummary) {
        TripAiSummary summary = tripAiSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new RuntimeException("AI 总结不存在，summaryId: " + summaryId));

        if (overview != null) {
            summary.setOverview(overview);
        }
        if (highlights != null) {
            summary.setHighlights(highlights);
        }
        if (bestMoment != null) {
            summary.setBestMoment(bestMoment);
        }
        if (routeSummary != null) {
            summary.setRouteSummary(routeSummary);
        }
        summary.setUpdatedAt(new Date());

        return tripAiSummaryRepository.save(summary);
    }

    @Override
    @Transactional
    public TripAiSummary updateModelInfo(Long summaryId, String modelName, String version) {
        TripAiSummary summary = tripAiSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new RuntimeException("AI 总结不存在，summaryId: " + summaryId));

        if (modelName != null) {
            summary.setModelName(modelName);
        }
        if (version != null) {
            summary.setVersion(version);
        }
        summary.setUpdatedAt(new Date());

        return tripAiSummaryRepository.save(summary);
    }

    @Override
    @Transactional
    public void deleteSummary(Long summaryId) {
        tripAiSummaryRepository.deleteById(summaryId);
    }

    @Override
    @Transactional
    public void deleteSummaryByTrip(Long tripId) {
        tripAiSummaryRepository.deleteByTripId(tripId);
    }

    @Override
    @Transactional
    public void deleteSummaryByUserAndTrip(Long userId, Long tripId) {
        tripAiSummaryRepository.deleteByUserIdAndTripId(userId, tripId);
    }
}
