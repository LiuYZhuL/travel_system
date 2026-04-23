package com.travel.travel_system.component;

import com.travel.travel_system.service.impl.TripAggregationRefreshService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class TripAggregationRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripAggregationRefreshScheduler.class);

    @Autowired
    private TripAggregationRefreshService tripAggregationRefreshService;

    @Scheduled(initialDelay = 10_000L, fixedDelay = 5_000L)
    public void scanDirtyTrips() {
        Set<String> dirtyKeys = tripAggregationRefreshService.scanDirtyKeys();
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }
        log.debug("[TRIP_AGG_SCHEDULE] dirtyKeyCount={}", dirtyKeys.size());
        for (String dirtyKey : dirtyKeys) {
            Long tripId = tripAggregationRefreshService.parseTripId(dirtyKey);
            if (tripId == null) {
                continue;
            }
            try {
                tripAggregationRefreshService.refreshTripAggregationIfReady(tripId);
            } catch (Exception e) {
                log.warn("[TRIP_AGG_SCHEDULE] refresh failed tripId={}: {}", tripId, e.getMessage(), e);
            }
        }
    }
}
