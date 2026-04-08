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
    private static final long FINALIZE_LOCK_TTL_SECONDS = 10 * 60L;

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
        if (tripId == null) {
            throw new RuntimeException("tripId 不能为空");
        }

        String lockKey = "track_match:finalize:lock:" + tripId;
        String lockValue = UUID.randomUUID().toString();
        if (!redisService.setIfAbsent(lockKey, lockValue, FINALIZE_LOCK_TTL_SECONDS)) {
            throw new RuntimeException("路线快照收口正在进行中，tripId=" + tripId);
        }

        try {
            trackPointService.recomputeTripMatchIfNeeded(tripId);
            TripRouteSnapshotPayload payload = trackPointService.buildRouteSnapshotPayload(tripId);
            if (payload.getMatchedResults() == null || payload.getMatchedResults().isEmpty()) {
                throw new RuntimeException("最终路线快照为空，tripId=" + tripId);
            }

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            byte[] gzipBytes = gzip(jsonBytes);
            String contentHash = sha256Hex(gzipBytes);
            String objectName = buildObjectName(payload);

            Optional<TripRouteSnapshot> existingOpt = tripRouteSnapshotRepository.findById(tripId);
            if (existingOpt.isPresent()) {
                TripRouteSnapshot existing = existingOpt.get();
                if (payload.getFingerprint().equals(existing.getFingerprint())
                        && payload.getAlgoVersion().equals(existing.getAlgoVersion())
                        && contentHash.equals(existing.getContentHash())
                        && existing.getOssObjectKey() != null
                        && !existing.getOssObjectKey().isBlank()) {
                    trackPointService.warmLatestCacheFromSnapshot(payload);
                    return existing;
                }
            }

            String ossUrl = ossService.uploadFile(gzipBytes, objectName);

            TripRouteSnapshot snapshot = existingOpt.orElseGet(TripRouteSnapshot::new);
            Date now = new Date();
            snapshot.setTripId(tripId);
            snapshot.setRouteStatus("FINAL");
            snapshot.setAlgoVersion(payload.getAlgoVersion());
            snapshot.setFingerprint(payload.getFingerprint());
            snapshot.setPointCount(payload.getPointCount() == null ? 0 : payload.getPointCount());
            snapshot.setStartTs(payload.getStartTs());
            snapshot.setEndTs(payload.getEndTs());
            snapshot.setOverviewPolylineJson(objectMapper.writeValueAsString(payload.getReconstructedPolyline()));
            snapshot.setOssObjectKey(ossUrl);
            snapshot.setOssEtag(null);
            snapshot.setContentHash(contentHash);
            snapshot.setGeneratedAt(new Date(payload.getGeneratedAt() == null ? System.currentTimeMillis() : payload.getGeneratedAt()));
            if (snapshot.getCreatedAt() == null) {
                snapshot.setCreatedAt(now);
            }
            snapshot.setUpdatedAt(now);
            TripRouteSnapshot saved = tripRouteSnapshotRepository.save(snapshot);

            trackPointService.warmLatestCacheFromSnapshot(payload);
            return saved;
        } catch (Exception e) {
            log.error("[TRIP_ROUTE_SNAPSHOT] finalize failed tripId={}: {}", tripId, e.getMessage(), e);
            throw new RuntimeException("行程路线快照收口失败: " + e.getMessage(), e);
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

    private String buildObjectName(TripRouteSnapshotPayload payload) {
        String algo = sanitize(payload.getAlgoVersion());
        String fp = sanitize(payload.getFingerprint());
        return ossPrefix + "/" + payload.getTripId() + "/final/" + algo + "/" + fp + ".json.gz";
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
            log.warn("[TRIP_ROUTE_SNAPSHOT] release finalize lock failed key={}: {}", lockKey, e.getMessage(), e);
        }
    }
}
