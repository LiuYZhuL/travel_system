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
     * 生成行程总结（支持用户自定义风格提示词）
     * @param tripId     行程ID
     * @param userPrompt 用户自定义写作风格指令；为 null 时使用默认提示词
     * @return 行程总结信息
     */
    Map<String, Object> generateTripSummary(Long tripId, String userPrompt);

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

    Map<String, Object> regenerateTripSummary(Long tripId, String reason);

    /**
     * 重新生成行程总结（支持用户自定义风格提示词）
     * @param tripId     行程ID
     * @param reason     重新生成原因
     * @param userPrompt 用户自定义写作风格指令；为 null 时使用默认提示词
     */
    Map<String, Object> regenerateTripSummary(Long tripId, String reason, String userPrompt);

    List<Map<String, Object>> getAiSummaryHistory(Long tripId);

    Map<String, Object> rollbackAiSummary(Long tripId, Long summaryId);

    /**
     * 删除行程的所有 AI 总结记录，并清空行程的 summaryText 快照
     */
    void deleteAiSummary(Long tripId);

    /**
     * 删除指定版本的 AI 总结，若删除的是最新版则自动晋升次新版
     * @param tripId    行程 ID
     * @param summaryId 要删除的 AI 总结 ID
     */
    void deleteAiSummaryVersion(Long tripId, Long summaryId);
}
