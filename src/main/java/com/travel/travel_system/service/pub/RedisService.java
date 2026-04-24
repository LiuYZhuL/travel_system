package com.travel.travel_system.service.pub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.travel_system.model.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService {

    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);
    private static final long DEFAULT_TRACK_CACHE_TTL_SECONDS = 30 * 60L;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void addTokenToBlacklist(String token, long expiration) {
        logger.debug("将令牌加入黑名单，过期时间: {}秒", expiration);
        try {
            redisTemplate.opsForValue().set("blacklist:" + token, "", expiration, TimeUnit.SECONDS);
            logger.info("令牌加入黑名单成功");
        } catch (Exception e) {
            logger.error("令牌加入黑名单失败: {}", e.getMessage(), e);
        }
    }

    public boolean isTokenInBlacklist(String token) {
        try {
            Boolean exists = redisTemplate.hasKey("blacklist:" + token);
            boolean result = exists != null && exists;
            if (result) {
                logger.debug("令牌在黑名单中");
            }
            return result;
        } catch (Exception e) {
            logger.error("检查令牌黑名单失败: {}", e.getMessage(), e);
            return false;
        }
    }

    public void removeTokenFromBlacklist(String token) {
        logger.debug("从黑名单中移除令牌");
        try {
            redisTemplate.delete("blacklist:" + token);
            logger.info("令牌从黑名单中移除成功");
        } catch (Exception e) {
            logger.error("令牌从黑名单中移除失败: {}", e.getMessage(), e);
        }
    }

    // ---- OAuth State / Nonce 管理（防止授权码重放） ----

    /**
     * 存储一次性 state nonce，TTL 与微信 code 有效期对齐（5 分钟）
     */
    public void storeStateNonce(String state, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set("oauth_state:" + state, "1", ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("storeStateNonce 失败, state={}: {}", state, e.getMessage(), e);
        }
    }

    /**
     * 消耗 state nonce（原子性 get + delete，单次有效）
     * 返回 true 表示 state 合法且尚未使用
     */
    public boolean consumeStateNonce(String state) {
        try {
            String key = "oauth_state:" + state;
            Boolean existed = redisTemplate.hasKey(key);
            if (!Boolean.TRUE.equals(existed)) return false;
            redisTemplate.delete(key);
            return true;
        } catch (Exception e) {
            logger.error("consumeStateNonce 失败, state={}: {}", state, e.getMessage(), e);
            return false;
        }
    }

    // ---- Refresh Token 管理 ----

    public void storeRefreshToken(String openId, String refreshToken, long expiration) {
        try {
            redisTemplate.opsForValue().set("refresh_token:" + openId, refreshToken, expiration, TimeUnit.SECONDS);
            logger.debug("刷新令牌存储成功，openId={}", openId);
        } catch (Exception e) {
            logger.error("刷新令牌存储失败, openId={}: {}", openId, e.getMessage(), e);
        }
    }

    public String getStoredRefreshToken(String openId) {
        try {
            return redisTemplate.opsForValue().get("refresh_token:" + openId);
        } catch (Exception e) {
            logger.error("获取刷新令牌失败, openId={}: {}", openId, e.getMessage(), e);
            return null;
        }
    }

    public void deleteRefreshToken(String openId) {
        try {
            redisTemplate.delete("refresh_token:" + openId);
            logger.debug("刷新令牌已删除，openId={}", openId);
        } catch (Exception e) {
            logger.error("删除刷新令牌失败, openId={}: {}", openId, e.getMessage(), e);
        }
    }

    public void setString(String key, String value, long expirationSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, expirationSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Redis setString 失败, key={}: {}", key, e.getMessage(), e);
        }
    }

    public String getString(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            logger.error("Redis getString 失败, key={}: {}", key, e.getMessage(), e);
            return null;
        }
    }

    public void setJson(String key, Object value, long expirationSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, expirationSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Redis setJson 失败, key={}: {}", key, e.getMessage(), e);
        }
    }

    public <T> T getJson(String key, Class<T> clazz) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            logger.error("Redis getJson 失败, key={}: {}", key, e.getMessage(), e);
            return null;
        }
    }

    public <T> T getJson(String key, TypeReference<T> typeReference) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            logger.error("Redis getJson 失败, key={}: {}", key, e.getMessage(), e);
            return null;
        }
    }


    public boolean setIfAbsent(String key, String value, long expirationSeconds) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, expirationSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            logger.error("Redis setIfAbsent 失败, key={}: {}", key, e.getMessage(), e);
            return false;
        }
    }

    public boolean hasKey(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.error("Redis hasKey 失败, key={}: {}", key, e.getMessage(), e);
            return false;
        }
    }

    public Set<String> scanKeys(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys == null ? Collections.emptySet() : keys;
        } catch (Exception e) {
            logger.error("Redis scanKeys 失败, pattern={}: {}", pattern, e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    public void deleteKey(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.error("Redis deleteKey 失败, key={}: {}", key, e.getMessage(), e);
        }
    }

    public void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints) {
        cacheTrackPoints(tripId, trackPoints, DEFAULT_TRACK_CACHE_TTL_SECONDS);
    }

    public void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints, long ttlSeconds) {
        String key = trackPointKey(tripId);
        try {
            String json = objectMapper.writeValueAsString(trackPoints);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("缓存轨迹点失败, tripId={}: {}", tripId, e.getMessage(), e);
        }
    }

    public List<TrackPoint> getTrackPointsFromCache(Long tripId, long startTimestamp, long endTimestamp) {
        String key = trackPointKey(tripId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            List<TrackPoint> points = objectMapper.readValue(json, new TypeReference<List<TrackPoint>>() {});
            if (points == null || points.isEmpty()) {
                return Collections.emptyList();
            }
            return points.stream()
                    .filter(p -> p.getTs() != null && p.getTs() >= startTimestamp && p.getTs() <= endTimestamp)
                    .toList();
        } catch (Exception e) {
            logger.error("读取轨迹点缓存失败, tripId={}: {}", tripId, e.getMessage(), e);
            return null;
        }
    }

    public void setCacheExpiration(Long tripId, long timeout) {
        try {
            redisTemplate.expire(trackPointKey(tripId), timeout, TimeUnit.SECONDS);
            logger.info("缓存过期时间设置成功，tripId: {}, 超时时间: {}秒", tripId, timeout);
        } catch (Exception e) {
            logger.error("设置缓存过期时间失败: {}", e.getMessage(), e);
        }
    }

    private String trackPointKey(Long tripId) {
        return "track_points:" + tripId;
    }

}
