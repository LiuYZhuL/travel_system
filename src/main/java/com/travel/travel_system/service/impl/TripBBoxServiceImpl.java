package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.TripBBox;
import com.travel.travel_system.repository.TripBBoxRepository;
import com.travel.travel_system.service.TripBBoxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TripBBoxServiceImpl implements TripBBoxService {

    @Autowired
    private TripBBoxRepository tripBBoxRepository;

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    @Override
    @Transactional
    public TripBBox createBBox(TripBBox bbox) {
        return tripBBoxRepository.save(bbox);
    }

    @Override
    public Optional<TripBBox> getBBox(Long tripId) {
        return tripBBoxRepository.findByTripId(tripId);
    }

    @Override
    @Transactional
    public TripBBox getOrCreateBBox(Long tripId) {
        return tripBBoxRepository.findByTripIdOrCreate(tripId);
    }

    @Override
    @Transactional
    public TripBBox updateBBox(Long tripId, Float minLat, Float minLng, Float maxLat, Float maxLng) {
        TripBBox bbox = tripBBoxRepository.findByTripIdOrCreate(tripId);

        if (minLat != null) {
            bbox.setMinLat(minLat);
        }
        if (minLng != null) {
            bbox.setMinLng(minLng);
        }
        if (maxLat != null) {
            bbox.setMaxLat(maxLat);
        }
        if (maxLng != null) {
            bbox.setMaxLng(maxLng);
        }

        return tripBBoxRepository.save(bbox);
    }

    @Override
    @Transactional
    public TripBBox expandBBox(Long tripId, Float lat, Float lng) {
        if (lat == null || lng == null) {
            return getOrCreateBBox(tripId);
        }

        TripBBox bbox = tripBBoxRepository.findByTripIdOrCreate(tripId);

        if (bbox.getMinLat() == null || bbox.getMinLat() == 0.0f || lat < bbox.getMinLat()) {
            bbox.setMinLat(lat);
        }
        if (bbox.getMaxLat() == null || bbox.getMaxLat() == 0.0f || lat > bbox.getMaxLat()) {
            bbox.setMaxLat(lat);
        }
        if (bbox.getMinLng() == null || bbox.getMinLng() == 0.0f || lng < bbox.getMinLng()) {
            bbox.setMinLng(lng);
        }
        if (bbox.getMaxLng() == null || bbox.getMaxLng() == 0.0f || lng > bbox.getMaxLng()) {
            bbox.setMaxLng(lng);
        }

        return tripBBoxRepository.save(bbox);
    }

    @Override
    @Transactional
    public void deleteBBox(Long tripId) {
        tripBBoxRepository.deleteByTripId(tripId);
    }

    @Override
    public boolean isValidBBox(TripBBox bbox) {
        if (bbox == null) return false;
        if (bbox.getMinLat() == null || bbox.getMaxLat() == null ||
            bbox.getMinLng() == null || bbox.getMaxLng() == null) {
            return false;
        }
        return bbox.getMinLat() <= bbox.getMaxLat() && bbox.getMinLng() <= bbox.getMaxLng();
    }

    @Override
    public double[] getCenter(TripBBox bbox) {
        if (!isValidBBox(bbox)) {
            return new double[]{0.0, 0.0};
        }
        double centerLat = (bbox.getMinLat() + bbox.getMaxLat()) / 2.0;
        double centerLng = (bbox.getMinLng() + bbox.getMaxLng()) / 2.0;
        return new double[]{centerLat, centerLng};
    }

    @Override
    public double getDiagonalDistance(TripBBox bbox) {
        if (!isValidBBox(bbox)) {
            return 0.0;
        }

        double minLat = Math.toRadians(bbox.getMinLat());
        double maxLat = Math.toRadians(bbox.getMaxLat());
        double minLng = Math.toRadians(bbox.getMinLng());
        double maxLng = Math.toRadians(bbox.getMaxLng());

        double dLat = maxLat - minLat;
        double dLng = maxLng - minLng;

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(minLat) * Math.cos(maxLat) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }
}
