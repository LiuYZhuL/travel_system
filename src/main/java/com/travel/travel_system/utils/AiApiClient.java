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
            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", 1000);
            requestBody.put("n", 1);
            requestBody.put("stop", null);

            // 发送请求
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    apiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            // 处理响应
            if (responseEntity.getStatusCode() == HttpStatus.OK) {
                JSONObject responseJson = JSONObject.parseObject(responseEntity.getBody());
                if (responseJson.containsKey("choices")) {
                    return responseJson.getJSONArray("choices").getJSONObject(0).getString("text").trim();
                }
            }

        } catch (Exception e) {
            System.err.println("AI API调用失败: " + e.getMessage());
        }

        // 失败时返回默认内容
        return "AI生成失败，请稍后重试";
    }

    /**
     * 生成行程总结
     * @param tripData 行程数据
     * @return 行程总结
     */
    public String generateTripSummary(Map<String, Object> tripData) {
        String prompt = buildTripSummaryPrompt(tripData);
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

    /**
     * 构建行程总结提示词
     */
    private String buildTripSummaryPrompt(Map<String, Object> tripData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请为以下行程生成一个详细的总结：\n");
        prompt.append("行程标题：").append(tripData.getOrDefault("title", "未知行程")).append("\n");
        prompt.append("行程时间：").append(tripData.getOrDefault("startTime", "未知时间")).append("\n");
        prompt.append("行程地点：").append(tripData.getOrDefault("places", "未知地点")).append("\n");
        prompt.append("行程距离：").append(tripData.getOrDefault("distanceText", "未知距离")).append("\n");
        prompt.append("行程时长：").append(tripData.getOrDefault("durationText", "未知时长")).append("\n");
        prompt.append("\n请生成一个包含以下内容的总结：\n");
        prompt.append("1. 行程概述\n");
        prompt.append("2. 主要亮点\n");
        prompt.append("3. 总体感受\n");
        prompt.append("\n总结应该生动有趣，突出行程的特色和精彩瞬间。");
        return prompt.toString();
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
