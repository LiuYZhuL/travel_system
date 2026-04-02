package com.travel.travel_system.service;

import com.travel.travel_system.vo.HeatmapPointVO;
import com.travel.travel_system.vo.UserHeatmapVO;

import java.util.List;

/**
 * 足迹热力图服务。
 *
 * 先支持两类输出：
 * 1. 用户维度全局热力图（用于“我的主页”）；
 * 2. 单次行程热力图（用于 Trip 地图页）。
 */
public interface HeatmapService {

    /**
     * 构建用户热力图。
     *
     * @param userId 用户 ID
     * @param scope  热力图范围，例如 ALL / YEAR / MONTH / WEEK
     * @param gridMeters 网格边长（米），为空时使用默认值
     * @return 用户热力图结果
     */
    UserHeatmapVO buildUserHeatmap(Long userId, String scope, Integer gridMeters);

    /**
     * 构建单次行程热力点。
     *
     * @param tripId 行程 ID
     * @param gridMeters 网格边长（米），为空时使用默认值
     * @return 热力点列表
     */
    List<HeatmapPointVO> buildTripHeatmap(Long tripId, Integer gridMeters);

    /**
     * 清理指定用户的热力图缓存。
     */
    void evictUserHeatmap(Long userId);

    /**
     * 清理指定行程的热力图缓存。
     */
    void evictTripHeatmap(Long tripId);
}
