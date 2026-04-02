package com.travel.travel_system.service;

import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.StoryBlock;

import java.util.List;
import java.util.Map;

public interface AiService {
    /**
     * 生成行程总结
     * @param tripId 行程ID
     * @return 行程总结信息
     */
    Map<String, Object> generateTripSummary(Long tripId);

    /**
     * 重建故事流
     * @param tripId 行程ID
     * @return 重建的故事块列表
     */
    List<StoryBlock> rebuildStoryBlocks(Long tripId);

    /**
     * 生成故事块内容
     * @param tripId 行程ID
     * @param blockType 块类型
     * @param data 相关数据
     * @return 生成的故事块
     */
    StoryBlock generateStoryBlock(Long tripId, String blockType, Map<String, Object> data);

    /**
     * 分析行程数据
     * @param tripId 行程ID
     * @return 分析结果
     */
    Map<String, Object> analyzeTripData(Long tripId);

    /**
     * 生成行程建议
     * @param tripId 行程ID
     * @return 行程建议
     */
    List<String> generateTripSuggestions(Long tripId);
}
