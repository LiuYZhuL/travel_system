package com.travel.travel_system.utils;

import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class AiApiClient {

    @Value("${ai.api.url}")
    private String apiUrl;

    @Value("${ai.api.key}")
    private String apiKey;

    @Value("${ai.api.model}")
    private String model;

    private final RestTemplate restTemplate;

    public AiApiClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 调用AI API生成内容
     * @param prompt 提示词
     * @param temperature 温度参数
     * @return 生成的内容
     */
    public String generateContent(String prompt, double temperature) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);

            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", 1000);

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (responseEntity.getStatusCode() == HttpStatus.OK) {
                JSONObject responseJson = JSONObject.parseObject(responseEntity.getBody());
                if (responseJson.containsKey("choices")) {
                    return responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
                }
            }

        } catch (Exception e) {
            System.err.println("AI API调用失败: " + e.getMessage());
        }

        return "AI生成失败，请稍后重试";
    }

    /**
     * 生成行程总结（使用默认风格提示词）
     */
    public String generateTripSummary(Map<String, Object> tripData) {
        return generateTripSummary(tripData, null);
    }

    /**
     * 生成行程总结，支持用户自定义风格提示词。
     * @param tripData   行程数据
     * @param userPrompt 用户自定义风格指令；为 null 或空时使用默认提示词
     */
    public String generateTripSummary(Map<String, Object> tripData, String userPrompt) {
        String prompt = buildTripSummaryPrompt(tripData, userPrompt);
        return generateContent(prompt, 0.7);
    }

    /**
     * 生成故事块内容
     * @param blockType 块类型
     * @param data 相关数据
     * @return 故事块内容
     */
    public String generateStoryBlockContent(String blockType, Map<String, Object> data) {
        String prompt = buildStoryBlockPrompt(blockType, data);
        return generateContent(prompt, 0.8);
    }

    /**
     * 生成行程建议
     * @param tripData 行程数据
     * @return 行程建议
     */
    public String generateTripSuggestions(Map<String, Object> tripData) {
        String prompt = buildTripSuggestionsPrompt(tripData);
        return generateContent(prompt, 0.6);
    }

    private static final String DEFAULT_TRIP_SUMMARY_STYLE =
            "请用生动感性的语气，重点突出旅途中的特色体验与情感感受，文风简洁有温度，不超过150字。";

    /**
     * 构建行程总结提示词，支持用户自定义风格指令。
     * userPrompt 为空时使用默认风格。
     */
    private String buildTripSummaryPrompt(Map<String, Object> tripData, String userPrompt) {
        String styleInstruction = (userPrompt != null && !userPrompt.isBlank())
                ? userPrompt.trim()
                : DEFAULT_TRIP_SUMMARY_STYLE;

        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下行程数据生成一段旅行总结：\n\n");
        prompt.append("行程标题：").append(tripData.getOrDefault("title", "未知行程")).append("\n");
        prompt.append("出发时间：").append(tripData.getOrDefault("startTime", "未知时间")).append("\n");
        prompt.append("途经地点：").append(tripData.getOrDefault("places", "未知地点")).append("\n");
        prompt.append("行程距离：").append(tripData.getOrDefault("distanceText", "未知距离")).append("\n");
        prompt.append("行程时长：").append(tripData.getOrDefault("durationText", "未知时长")).append("\n");
        prompt.append("轨迹点数量：").append(tripData.getOrDefault("trackPointCount", 0)).append("\n");
        prompt.append("媒体数量：照片 ").append(tripData.getOrDefault("photoCount", 0))
                .append(" 张，视频 ").append(tripData.getOrDefault("videoCount", 0)).append(" 个\n");
        prompt.append("笔记数量：").append(tripData.getOrDefault("noteCount", 0)).append("\n");
        prompt.append("故事流块数量：").append(tripData.getOrDefault("storyBlockCount", 0)).append("\n");
        if (tripData.containsKey("longestStayPlace")) {
            prompt.append("停留最久的地点：").append(tripData.get("longestStayPlace"))
                  .append("（").append(tripData.getOrDefault("longestStayDuration", "")).append("）\n");
        }
        appendOptionalPromptLine(prompt, "旅行笔记摘录", tripData.get("notes"));
        appendOptionalPromptLine(prompt, "故事流摘录", tripData.get("storyBlocks"));
        prompt.append("\n【写作要求】\n").append(styleInstruction).append("\n");
        prompt.append("\n只围绕以上真实行程数据写作，不要编造不存在的地点、人物或事件。");
        prompt.append("\n只输出总结正文，不要加标题、编号或额外说明。");
        return prompt.toString();
    }

    private void appendOptionalPromptLine(StringBuilder prompt, String label, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        prompt.append(label).append("：").append(text).append("\n");
    }

    /**
     * 构建故事块提示词
     */
    private String buildStoryBlockPrompt(String blockType, Map<String, Object> data) {
        StringBuilder prompt = new StringBuilder();
        
        switch (blockType) {
            case "TEXT":
                prompt.append("请为以下内容生成一段生动的文本记录：\n");
                prompt.append("内容：").append(data.getOrDefault("content", "")).append("\n");
                prompt.append("\n请生成一段富有情感的文本，描述当时的情景和感受。");
                break;
            case "PLACE_SUMMARY":
                prompt.append("请为以下地点生成一个简短的总结：\n");
                prompt.append("地点名称：").append(data.getOrDefault("placeName", "未知地点")).append("\n");
                prompt.append("停留时间：").append(data.getOrDefault("duration", "未知时长")).append("\n");
                prompt.append("\n请生成一段描述该地点特色和游览感受的文字。");
                break;
            case "PHOTO":
                prompt.append("请为一张旅行照片生成一段描述：\n");
                prompt.append("拍摄地点：").append(data.getOrDefault("location", "未知地点")).append("\n");
                prompt.append("拍摄时间：").append(data.getOrDefault("time", "未知时间")).append("\n");
                prompt.append("\n请生成一段生动的照片描述，捕捉照片中的氛围和故事。");
                break;
            case "VIDEO":
                prompt.append("请为一段旅行视频生成一段描述：\n");
                prompt.append("视频地点：").append(data.getOrDefault("location", "未知地点")).append("\n");
                prompt.append("视频时长：").append(data.getOrDefault("duration", "未知时长")).append("\n");
                prompt.append("\n请生成一段生动的视频描述，突出视频中的精彩瞬间。");
                break;
            default:
                prompt.append("请生成一段旅行相关的内容。");
        }
        
        return prompt.toString();
    }

    /**
     * 构建行程建议提示词
     */
    private String buildTripSuggestionsPrompt(Map<String, Object> tripData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下行程提供一些实用的建议：\n");
        prompt.append("行程目的地：").append(tripData.getOrDefault("destination", "未知目的地")).append("\n");
        prompt.append("行程时间：").append(tripData.getOrDefault("startTime", "未知时间")).append("\n");
        prompt.append("行程类型：").append(tripData.getOrDefault("type", "普通旅行")).append("\n");
        prompt.append("\n请提供以下方面的建议：\n");
        prompt.append("1. 最佳游览时间\n");
        prompt.append("2. 交通建议\n");
        prompt.append("3. 美食推荐\n");
        prompt.append("4. 景点推荐\n");
        prompt.append("5. 其他实用建议\n");
        return prompt.toString();
    }
}
