package com.travel.travel_system.component;

import com.travel.travel_system.service.impl.TrackPointServiceImpl;
import com.travel.travel_system.service.pub.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 扫描轨迹匹配脏标记，并触发最新匹配结果重算。
 *
 * 业务流程：
 * 1. 轨迹点写库后，TrackPointServiceImpl.markTripMatchDirty(tripId)
 * 2. 本调度器定时扫描 track_match:dirty:*
 * 3. 调用 TrackPointServiceImpl.recomputeTripMatchIfNeeded(tripId)
 * 4. 成功后写入 track_match:latest:{tripId} 并清理 dirty 标记
 */
@Component
public class TripMatchRecomputeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TripMatchRecomputeScheduler.class);
    private static final String DIRTY_KEY_PATTERN = "track_match:dirty:*";

    @Autowired
    private RedisService redisService;

    @Autowired
    private TrackPointServiceImpl trackPointService;

    /**
     * 固定延迟扫描 dirty trip。
     *
     * initialDelay: 应用启动后延迟 10 秒开始
     * fixedDelay: 上次执行结束后 15 秒再执行下一轮
     */
    @Scheduled(initialDelay = 10_000L, fixedDelay = 15_000L)
    public void scanDirtyTrips() {
        Set<String> dirtyKeys = redisService.scanKeys(DIRTY_KEY_PATTERN);
        if (dirtyKeys == null || dirtyKeys.isEmpty()) {
            return;
        }

        log.warn("[TRACK_MATCH_SCHEDULE] dirtyKeyCount={}", dirtyKeys.size());
        for (String dirtyKey : dirtyKeys) {
            Long tripId = parseTripId(dirtyKey);
            if (tripId == null) {
                log.warn("[TRACK_MATCH_SCHEDULE] skip invalid dirtyKey={}", dirtyKey);
                continue;
            }

            try {
                boolean recomputed = trackPointService.recomputeTripMatchIfNeeded(tripId);
                log.warn("[TRACK_MATCH_SCHEDULE] tripId={} recomputed={}", tripId, recomputed);
            } catch (Exception e) {
                log.warn("[TRACK_MATCH_SCHEDULE] recompute failed tripId={}: {}", tripId, e.getMessage(), e);
            }
        }
    }

    private Long parseTripId(String dirtyKey) {
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
}
