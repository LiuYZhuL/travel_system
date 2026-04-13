package com.travel.travel_system.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.travel_system.dto.TripRouteSnapshotPayload;
import com.travel.travel_system.model.TripRouteSnapshot;
import com.travel.travel_system.repository.TripRouteSnapshotRepository;
import com.travel.travel_system.service.TripRouteSnapshotService;
import com.travel.travel_system.service.pub.OssService;
import com.travel.travel_system.service.pub.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class TripRouteSnapshotServiceImpl implements TripRouteSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(TripRouteSnapshotServiceImpl.class);
    private static final long SNAPSHOT_LOCK_TTL_SECONDS = 10 * 60L;

    @Autowired
    private TripRouteSnapshotRepository tripRouteSnapshotRepository;

    @Autowired
    private TrackPointServiceImpl trackPointService;

    @Autowired
    private RedisService redisService;

    @Autowired
    private OssService ossService;

    @Value("${app.route-snapshot.oss-prefix:trip-routes}")
    private String ossPrefix;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public TripRouteSnapshot finalizeFinishedTrip(Long tripId) {
        // 现在“结束收口”也只是在保存最新展示快照
        return saveLatestSnapshot(tripId);
    }

    @Override
    @Transactional
    public TripRouteSnapshot saveLatestSnapshot(Long tripId) {
        if (tripId == null) {
            throw new RuntimeException("tripId 不能为空");
        }

        String lockKey = "track_match:snapshot:lock:" + tripId;
        String lockValue = UUID.randomUUID().toString();
        if (!redisService.setIfAbsent(lockKey, lockValue, SNAPSHOT_LOCK_TTL_SECONDS)) {
            throw new RuntimeException("路线快照保存正在进行中，tripId=" + tripId);
        }

        try {
            trackPointService.recomputeTripMatchIfNeeded(tripId);
            TripRouteSnapshotPayload payload = trackPointService.buildRouteSnapshotPayload(tripId);
            boolean hasMatchedResults = payload.getMatchedResults() != null && !payload.getMatchedResults().isEmpty();

            Optional<TripRouteSnapshot> existingOpt = tripRouteSnapshotRepository.findById(tripId);
            if (!hasMatchedResults) {
                log.info("[TRIP_ROUTE_SNAPSHOT] latest payload is empty, keep snapshot safe tripId={}", tripId);
                return persistEmptySnapshot(tripId, payload, existingOpt.orElse(null));
            }

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            byte[] gzipBytes = gzip(jsonBytes);
            String contentHash = sha256Hex(gzipBytes);
            String objectName = buildLatestObjectName(payload);

            if (existingOpt.isPresent()) {
                TripRouteSnapshot existing = existingOpt.get();
                if (payload.getFingerprint().equals(existing.getFingerprint())
                        && payload.getAlgoVersion().equals(existing.getAlgoVersion())
                        && contentHash.equals(existing.getContentHash())
                        && existing.getOssObjectKey() != null
                        && !existing.getOssObjectKey().isBlank()) {
                    // 指纹未变，不重新上传，只回灌缓存
                    if (!"LATEST".equalsIgnoreCase(existing.getRouteStatus())) {
                        existing.setRouteStatus("LATEST");
                        existing.setUpdatedAt(new Date());
                        tripRouteSnapshotRepository.save(existing);
                    }
                    trackPointService.warmLatestCacheFromSnapshot(payload);
                    return existing;
                }
            }

            String ossUrl = ossService.uploadFile(gzipBytes, objectName);

            TripRouteSnapshot snapshot = existingOpt.orElseGet(TripRouteSnapshot::new);
            Date now = new Date();
            snapshot.setTripId(tripId);
            snapshot.setRouteStatus("LATEST");
            snapshot.setAlgoVersion(payload.getAlgoVersion());
            snapshot.setFingerprint(payload.getFingerprint());
            snapshot.setPointCount(payload.getPointCount() == null ? 0 : payload.getPointCount());
            snapshot.setStartTs(payload.getStartTs());
            snapshot.setEndTs(payload.getEndTs());
            snapshot.setOverviewPolylineJson(objectMapper.writeValueAsString(payload.getReconstructedPolyline()));
            snapshot.setOssObjectKey(ossUrl);
            snapshot.setOssEtag(null);
            snapshot.setContentHash(contentHash);
            snapshot.setGeneratedAt(new Date(
                    payload.getGeneratedAt() == null ? System.currentTimeMillis() : payload.getGeneratedAt()
            ));

            // 如果你的 entity 已经有这两个字段，可以顺手补上；没有就删掉这两行
            // snapshot.setMediaPointCount(payload.getMediaPointCount() == null ? 0 : payload.getMediaPointCount());
            // snapshot.setSegmentCount(payload.getSegmentCount() == null ? 0 : payload.getSegmentCount());

            if (snapshot.getCreatedAt() == null) {
                snapshot.setCreatedAt(now);
            }
            snapshot.setUpdatedAt(now);

            TripRouteSnapshot saved = tripRouteSnapshotRepository.save(snapshot);
            trackPointService.warmLatestCacheFromSnapshot(payload);
            return saved;
        } catch (Exception e) {
            log.error("[TRIP_ROUTE_SNAPSHOT] save latest failed tripId={}: {}", tripId, e.getMessage(), e);

            tripRouteSnapshotRepository.findById(tripId).ifPresent(snapshot -> {
                snapshot.setRouteStatus("FAILED");
                snapshot.setUpdatedAt(new Date());
                tripRouteSnapshotRepository.save(snapshot);
            });

            throw new RuntimeException("行程路线快照保存失败: " + e.getMessage(), e);
        } finally {
            releaseLockSafely(lockKey, lockValue);
        }
    }

    @Override
    public Optional<TripRouteSnapshot> getSnapshotMeta(Long tripId) {
        return tripRouteSnapshotRepository.findById(tripId);
    }

    @Override
    public Optional<TripRouteSnapshotPayload> loadSnapshotPayload(Long tripId) {
        Optional<TripRouteSnapshot> snapshotOpt = tripRouteSnapshotRepository.findById(tripId);
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        TripRouteSnapshot snapshot = snapshotOpt.get();
        if (snapshot.getOssObjectKey() == null || snapshot.getOssObjectKey().isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] gzipBytes = ossService.getFileBytesByUrl(snapshot.getOssObjectKey());
            byte[] jsonBytes = gunzip(gzipBytes);
            return Optional.of(objectMapper.readValue(jsonBytes, TripRouteSnapshotPayload.class));
        } catch (Exception e) {
            log.error("[TRIP_ROUTE_SNAPSHOT] load payload failed tripId={}: {}", tripId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<TripRouteSnapshotPayload> loadSnapshotPayloadAndWarmRedis(Long tripId) {
        Optional<TripRouteSnapshotPayload> payloadOpt = loadSnapshotPayload(tripId);
        payloadOpt.ifPresent(trackPointService::warmLatestCacheFromSnapshot);
        return payloadOpt;
    }

    private String buildLatestObjectName(TripRouteSnapshotPayload payload) {
        String algo = sanitize(payload.getAlgoVersion());
        String fp = sanitize(payload.getFingerprint());
        return ossPrefix + "/" + payload.getTripId() + "/latest/" + algo + "/" + fp + ".json.gz";
    }

    private String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        return text.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }

    private byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }

    private byte[] gunzip(byte[] input) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return gzip.readAllBytes();
        }
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private void releaseLockSafely(String lockKey, String expectedValue) {
        try {
            String currentValue = redisService.getString(lockKey);
            if (expectedValue.equals(currentValue)) {
                redisService.deleteKey(lockKey);
            }
        } catch (Exception e) {
            log.warn("[TRIP_ROUTE_SNAPSHOT] release snapshot lock failed key={}: {}", lockKey, e.getMessage(), e);
        }
    }

    private TripRouteSnapshot persistEmptySnapshot(Long tripId,
                                                   TripRouteSnapshotPayload payload,
                                                   TripRouteSnapshot existing) {
        if (existing != null && existing.getOssObjectKey() != null && !existing.getOssObjectKey().isBlank()) {
            return existing;
        }

        TripRouteSnapshot snapshot = existing == null ? new TripRouteSnapshot() : existing;
        Date now = new Date();
        snapshot.setTripId(tripId);
        snapshot.setRouteStatus("EMPTY");
        snapshot.setAlgoVersion(payload.getAlgoVersion());
        snapshot.setFingerprint(payload.getFingerprint());
        snapshot.setPointCount(payload.getPointCount() == null ? 0 : payload.getPointCount());
        snapshot.setStartTs(payload.getStartTs());
        snapshot.setEndTs(payload.getEndTs());
        snapshot.setOverviewPolylineJson(null);
        snapshot.setOssObjectKey(null);
        snapshot.setOssEtag(null);
        snapshot.setContentHash(null);
        snapshot.setGeneratedAt(new Date(
                payload.getGeneratedAt() == null ? System.currentTimeMillis() : payload.getGeneratedAt()
        ));
        if (snapshot.getCreatedAt() == null) {
            snapshot.setCreatedAt(now);
        }
        snapshot.setUpdatedAt(now);
        return tripRouteSnapshotRepository.save(snapshot);
    }
}
