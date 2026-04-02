package com.travel.travel_system.service.pub;

import com.travel.travel_system.model.TrackPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService  {

    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 将令牌加入黑名单
     * @param token 令牌
     * @param expiration 过期时间（秒）
     */
    public void addTokenToBlacklist(String token, long expiration) {
        logger.debug("将令牌加入黑名单，过期时间: {}秒", expiration);
        try {
            redisTemplate.opsForValue().set("blacklist:" + token, "", expiration, TimeUnit.SECONDS);
            logger.info("令牌加入黑名单成功");
        } catch (Exception e) {
            logger.error("令牌加入黑名单失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检查令牌是否在黑名单中
     * @param token 令牌
     * @return 是否在黑名单中
     */
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
            // 出错时返回false，避免因Redis问题导致正常令牌无法使用
            return false;
        }
    }

    /**
     * 从黑名单中移除令牌
     * @param token 令牌
     */
    public void removeTokenFromBlacklist(String token) {
        logger.debug("从黑名单中移除令牌");
        try {
            redisTemplate.delete("blacklist:" + token);
            logger.info("令牌从黑名单中移除成功");
        } catch (Exception e) {
            logger.error("令牌从黑名单中移除失败: {}", e.getMessage(), e);
        }
    }


    /**
     * 将轨迹点缓存到 Redis
     * @param tripId 行程 ID
     * @param trackPoints 轨迹点数据
     */
    public void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints) {
        // 实现轨迹点缓存逻辑
    }

    /**
     * 从 Redis 获取缓存的轨迹点
     * @param tripId 行程 ID
     * @param startTimestamp 起始时间戳
     * @param endTimestamp 结束时间戳
     * @return 轨迹点列表
     */
    public List<TrackPoint> getTrackPointsFromCache(Long tripId, long startTimestamp, long endTimestamp) {
        return null;
    }

    /**
     * 设置缓存过期时间
     * @param tripId 行程 ID
     * @param timeout 超过时间
     */
    public void setCacheExpiration(Long tripId, long timeout) {
        try {
            redisTemplate.expire("track_points:" + tripId, timeout, TimeUnit.SECONDS);
            logger.info("缓存过期时间设置成功，tripId: {}, 超时时间: {}秒", tripId, timeout);
        } catch (Exception e) {
            logger.error("设置缓存过期时间失败: {}", e.getMessage(), e);
        }
    }
}
