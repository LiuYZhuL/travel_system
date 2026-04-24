package com.travel.travel_system.service.impl;

import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.service.AiService;
import com.travel.travel_system.service.PlaceSummaryService;
import com.travel.travel_system.service.pub.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TripAggregationRefreshService {

    private static final Logger log = LoggerFactory.getLogger(TripAggregationRefreshService.class);
    public static final String DIRTY_KEY_PATTERN = "trip_aggregation:dirty:*";
    private static final String DIRTY_KEY_PREFIX = "trip_aggregation:dirty:";
    private static final String LOCK_KEY_PREFIX = "trip_aggregation:lock:";

    @Autowired
    private RedisService redisService;
    @Autowired
    private PlaceSummaryService placeSummaryService;
    @Autowired
    private AiService aiService;
    @Autowired
    private TripRepository tripRepository;

    @Value("${app.trip.aggregation.quiet-ms:12000}")
    private long aggregationQuietMs;

    @Value("${app.trip.aggregation.batch-threshold:6}")
    private int aggregationBatchThreshold;

    @Value("${app.trip.aggregation.lock-ttl-seconds:180}")
    private long aggregationLockTtlSeconds;

    @Value("${app.trip.aggregation.dirty-ttl-seconds:86400}")
    private long aggregationDirtyTtlSeconds;

    public void markTripDirty(Long tripId, String reason) {
        if (tripId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        AggregationDirtyState state = redisService.getJson(dirtyKey(tripId), AggregationDirtyState.class);
        if (state == null) {
            state = new AggregationDirtyState();
            state.tripId = tripId;
            state.firstDirtyAt = now;
            state.reasons = new ArrayList<>();
        }
        state.lastDirtyAt = now;
        state.changeCount = Math.max(0, state.changeCount) + 1;
        appendReason(state, reason);
        redisService.setJson(dirtyKey(tripId), state, aggregationDirtyTtlSeconds);
        log.debug("[TRIP_AGG_DIRTY] mark tripId={} reason={} count={}", tripId, reason, state.changeCount);
    }

    public boolean refreshTripAggregationIfReady(Long tripId) {
        if (tripId == null) {
            return false;
        }
        AggregationDirtyState pending = redisService.getJson(dirtyKey(tripId), AggregationDirtyState.class);
        if (pending == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (!shouldProcess(pending, now)) {
            return false;
        }
        if (!tripRepository.existsById(tripId)) {
            redisService.deleteKey(dirtyKey(tripId));
            return false;
        }

        String lockKey = lockKey(tripId);
        String lockValue = UUID.randomUUID().toString();
        if (!redisService.setIfAbsent(lockKey, lockValue, aggregationLockTtlSeconds)) {
            log.debug("[TRIP_AGG_REFRESH] skip locked tripId={}", tripId);
            return false;
        }
        try {
            AggregationDirtyState latest = redisService.getJson(dirtyKey(tripId), AggregationDirtyState.class);
            if (latest == null || !shouldProcess(latest, System.currentTimeMillis())) {
                return false;
            }

            placeSummaryService.generatePlaceSummariesForTrip(tripId);
            aiService.rebuildStoryBlocks(tripId);

            AggregationDirtyState after = redisService.getJson(dirtyKey(tripId), AggregationDirtyState.class);
            if (after == null) {
                return true;
            }
            if (after.lastDirtyAt <= latest.lastDirtyAt && after.changeCount <= latest.changeCount) {
                redisService.deleteKey(dirtyKey(tripId));
            }
            log.info("[TRIP_AGG_REFRESH] tripId={} refreshed count={} reasons={}", tripId, latest.changeCount, latest.reasons);
            return true;
        } catch (Exception e) {
            log.warn("[TRIP_AGG_REFRESH] failed tripId={}: {}", tripId, e.getMessage(), e);
            return false;
        } finally {
            releaseLockSafely(lockKey, lockValue);
        }
    }

    public Set<String> scanDirtyKeys() {
        return redisService.scanKeys(DIRTY_KEY_PATTERN);
    }

    public Long parseTripId(String dirtyKey) {
        if (dirtyKey == null || dirtyKey.isBlank()) {
            return null;
        }
        int lastColon = dirtyKey.lastIndexOf(':');
        if (lastColon < 0 || lastColon >= dirtyKey.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(dirtyKey.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean shouldProcess(AggregationDirtyState state, long now) {
        if (state == null || state.lastDirtyAt <= 0L) {
            return false;
        }
        boolean quietEnough = now - state.lastDirtyAt >= Math.max(aggregationQuietMs, 0L);
        boolean batchEnough = state.changeCount >= Math.max(aggregationBatchThreshold, 1);
        return quietEnough || batchEnough;
    }

    private void appendReason(AggregationDirtyState state, String reason) {
        if (state == null) {
            return;
        }
        if (state.reasons == null) {
            state.reasons = new ArrayList<>();
        }
        String normalized = reason == null ? "UNKNOWN" : reason.trim().toUpperCase();
        if (normalized.isEmpty()) {
            normalized = "UNKNOWN";
        }
        if (!state.reasons.contains(normalized)) {
            state.reasons.add(normalized);
        }
        if (state.reasons.size() > 8) {
            state.reasons = new ArrayList<>(state.reasons.subList(state.reasons.size() - 8, state.reasons.size()));
        }
    }

    private void releaseLockSafely(String lockKey, String lockValue) {
        String current = redisService.getString(lockKey);
        if (current == null || current.equals(lockValue)) {
            redisService.deleteKey(lockKey);
        }
    }

    private long parseLong(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String dirtyKey(Long tripId) {
        return DIRTY_KEY_PREFIX + tripId;
    }

    private String lockKey(Long tripId) {
        return LOCK_KEY_PREFIX + tripId;
    }

    public static class AggregationDirtyState {
        public Long tripId;
        public long firstDirtyAt;
        public long lastDirtyAt;
        public int changeCount;
        public List<String> reasons;
    }
}
