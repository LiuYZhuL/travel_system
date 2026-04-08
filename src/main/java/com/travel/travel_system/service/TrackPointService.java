package com.travel.travel_system.service;

import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.dto.MapMatchingResult;
import com.travel.travel_system.vo.TrackPolylineVO;

import java.util.*;

public interface TrackPointService {
    /**
     * 缓存轨迹点数据到 Redis
     * @param tripId 行程 ID
     * @param trackPoints 轨迹点列表
     */
    void cacheTrackPoints(Long tripId, List<TrackPoint> trackPoints);

    /**
     * 查询指定行程的轨迹点
     * @param tripId 行程 ID
     * @param startTimestamp 起始时间戳
     * @param endTimestamp 结束时间戳
     * @return 轨迹点列表
     */
    List<TrackPoint> getTrackPoints(Long tripId, long startTimestamp, long endTimestamp);

    /**
     * 对轨迹点数据进行平滑处理
     * @param trackPoints 轨迹点列表
     * @return 平滑后的轨迹点列表
     */
    List<TrackPoint> smoothTrackPoints(List<TrackPoint> trackPoints);

    /**
     * 按时间戳范围获取轨迹点
     * @param tripId 行程 ID
     * @param startTimestamp 起始时间戳
     * @param endTimestamp 结束时间戳
     * @return 轨迹点列表
     */
    List<TrackPoint> getTrackPointsByRange(Long tripId, long startTimestamp, long endTimestamp);

    /**
     * 执行地图匹配（纠偏、抓路）
     * @param tripId 行程 ID
     * @return 匹配结果列表
     */
    List<MapMatchingResult> matchTrajectory(Long tripId);

    /**
     * 生成模拟轨迹点（用于测试）
     * @param tripId 行程 ID
     * @return 模拟轨迹点列表
     */
    List<java.util.Map<String, Object>> generateMockTrackPoints(Long tripId);

    /**
     * 使用 HMM 算法处理轨迹点（地图匹配 + 路径重构）
     * @param tripId 行程 ID
     * @param originalPoints 原始轨迹点
     * @return 处理结果，包含原始轨迹、匹配投影轨迹、路径重构轨迹
     */
    Map<String, TrackPolylineVO> processTrackPoints(Long tripId, List<Map<String, Object>> originalPoints);

    /**
     * 新版接口：多段轨迹 + 媒体点 + 分段渲染结果
     *
     * 返回建议结构：
     * {
     *   "rawSegments": List<TrackPolylineVO>,
     *   "matchedSegments": List<TrackPolylineVO>,
     *   "reconstructedSegments": List<TrackPolylineVO>,
     *   "mediaMarkers": List<MapMarkerVO>
     * }
     */
    Map<String, Object> processTrackRendering(Long tripId);

    /**
     * 标记该 trip 的匹配缓存/路线快照为脏
     */
    void markTripMatchDirty(Long tripId);
}
