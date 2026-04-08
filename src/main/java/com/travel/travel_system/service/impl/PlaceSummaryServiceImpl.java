package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.PlaceSummaryRepository;
import com.travel.travel_system.service.PlaceSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class PlaceSummaryServiceImpl implements PlaceSummaryService {

    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;

    @Override
    @Transactional
    public PlaceSummary createPlaceSummary(PlaceSummary placeSummary) {
        if (placeSummary.getCreatedAt() == null) {
            placeSummary.setCreatedAt(new Date());
        }
        if (placeSummary.getPrivacyLevel() == null) {
            placeSummary.setPrivacyLevel(PrivacyMode.PUBLIC);
        }
        return placeSummaryRepository.save(placeSummary);
    }

    @Override
    public Optional<PlaceSummary> getPlaceSummary(Long placeId) {
        return placeSummaryRepository.findById(placeId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByTrip(Long tripId) {
        return placeSummaryRepository.findByTripIdOrderByStartTimeAsc(tripId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByTripOrderByDuration(Long tripId) {
        return placeSummaryRepository.findByTripIdOrderByDurationSecDesc(tripId);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByCity(Long tripId, String city) {
        return placeSummaryRepository.findByTripIdAndCity(tripId, city);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesByDistrict(Long tripId, String district) {
        return placeSummaryRepository.findByTripIdAndDistrict(tripId, district);
    }

    @Override
    public List<PlaceSummary> getPlaceSummariesWithCover(Long tripId) {
        return placeSummaryRepository.findByTripIdAndPhotoCoverIdIsNotNull(tripId);
    }

    @Override
    public List<PlaceSummary> getLongStayPlaces(Long tripId, Long minDurationSec) {
        return placeSummaryRepository.findByTripIdAndDurationSecGreaterThanEqual(tripId, minDurationSec);
    }

    @Override
    public List<PlaceSummary> searchByPoiName(Long tripId, String keyword) {
        return placeSummaryRepository.findByTripIdAndPoiNameContaining(tripId, keyword);
    }

    @Override
    @Transactional
    public PlaceSummary updatePlaceSummary(Long placeId, String poiName, String userNotes, String userTags, PrivacyMode privacyLevel) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

        if (poiName != null) {
            place.setPoiName(poiName);
        }
        if (userNotes != null) {
            place.setUserNotes(userNotes);
        }
        if (userTags != null) {
            place.setUserTags(userTags);
        }
        if (privacyLevel != null) {
            place.setPrivacyLevel(privacyLevel);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public PlaceSummary updateCover(Long placeId, Long photoCoverId, Long videoCoverId) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

        if (photoCoverId != null) {
            place.setPhotoCoverId(photoCoverId);
        }
        if (videoCoverId != null) {
            place.setVideoCoverId(videoCoverId);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public PlaceSummary updateMediaCount(Long placeId, Integer photoCount, Integer videoCount) {
        PlaceSummary place = placeSummaryRepository.findById(placeId)
                .orElseThrow(() -> new RuntimeException("地点摘要不存在，placeId: " + placeId));

        if (photoCount != null) {
            place.setPhotoCount(photoCount);
        }
        if (videoCount != null) {
            place.setVideoCount(videoCount);
        }
        place.setUpdatedAt(new Date());

        return placeSummaryRepository.save(place);
    }

    @Override
    @Transactional
    public void deletePlaceSummary(Long placeId) {
        placeSummaryRepository.deleteById(placeId);
    }

    @Override
    @Transactional
    public void deletePlaceSummariesByTrip(Long tripId) {
        placeSummaryRepository.deleteByTripId(tripId);
    }

    @Override
    public long countByTrip(Long tripId) {
        return placeSummaryRepository.countByTripId(tripId);
    }
}
