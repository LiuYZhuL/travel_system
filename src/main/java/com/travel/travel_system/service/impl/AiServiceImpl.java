package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.*;
import com.travel.travel_system.model.enums.BlockType;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.*;
import com.travel.travel_system.service.AiService;
import com.travel.travel_system.utils.AiApiClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiServiceImpl implements AiService {

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private TripNoteRepository tripNoteRepository;

    @Autowired
    private StoryBlockRepository storyBlockRepository;

    @Autowired
    private TripAiSummaryRepository tripAiSummaryRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private AiApiClient aiApiClient;

    @Override
    @Transactional
    public Map<String, Object> generateTripSummary(Long tripId) {
        // 1. 获取行程信息
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 2. 获取地点摘要列表
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);

        // 3. 获取媒体统计
        Long photoCount = photoRepository.countByTripId(tripId);
        Long videoCount = videoRepository.countByTripId(tripId);

        // 4. 构建行程数据用于 AI 生成
        Map<String, Object> tripData = new HashMap<>();
        tripData.put("tripId", tripId);
        tripData.put("title", trip.getTitle());
        tripData.put("startTime", formatDateTime(trip.getStartTime()));
        tripData.put("endTime", trip.getEndTime() != null ? formatDateTime(trip.getEndTime()) : null);
        tripData.put("distanceText", formatDistance(trip.getDistanceM()));
        tripData.put("durationText", formatDuration(trip.getDurationSec()));
        
        // 5. 提取地点信息
        List<String> placeNames = places.stream()
                .map(PlaceSummary::getPoiName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        tripData.put("places", String.join(", ", placeNames));
        
        // 6. 找出停留时间最长的地点
        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong(PlaceSummary::getDurationSec))
                .orElse(null);
        if (longestStay != null) {
            tripData.put("longestStayPlace", longestStay.getPoiName());
            tripData.put("longestStayDuration", formatDuration(longestStay.getDurationSec()));
        }

        // 7. 调用 AI API 生成总结
        String aiSummary = aiApiClient.generateTripSummary(tripData);

        // 8. 解析 AI 生成的总结并构建亮点
        Map<String, Object> summary = new HashMap<>();
        summary.put("tripId", tripId);
        summary.put("overview", aiSummary != null && !aiSummary.isEmpty() ? aiSummary : buildDefaultSummary(trip, places));
        
        // 9. 生成亮点列表
        List<String> highlights = buildHighlights(trip, places, photoCount, videoCount);
        summary.put("highlights", highlights);
        
        // 10. 生成路线总结
        String routeSummary = buildRouteSummary(places);
        summary.put("routeSummary", routeSummary);
        
        // 11. 生成最佳时刻
        String bestMoment = buildBestMoment(places, photoCount);
        summary.put("bestMoment", bestMoment);
        
        summary.put("generatedAt", new Date());
        summary.put("version", "v1.0");

        // 12. 保存到数据库
        saveTripAiSummary(tripId, trip.getUserId(), summary);

        return summary;
    }

    @Override
    @Transactional
    public List<StoryBlock> rebuildStoryBlocks(Long tripId) {
        // 1. 获取行程信息
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 2. 删除旧的故事块
        List<StoryBlock> oldBlocks = storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);
        storyBlockRepository.deleteAll(oldBlocks);

        List<StoryBlock> newBlocks = new ArrayList<>();
        int sortIndex = 0;

        // 3. 获取地点摘要列表
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        
        // 4. 获取旅程笔记列表
        List<TripNote> notes = tripNoteRepository.findByTripIdOrderByCreatedAtDesc(tripId);

        // 5. 获取照片列表
        List<Photo> photos = photoRepository.findByTripId(tripId);

        // 6. 获取视频列表
        List<Video> videos = videoRepository.findByTripId(tripId);

        // 7. 创建行程开始故事块
        if (trip.getStartTime() != null) {
            StoryBlock startBlock = createStoryBlock(
                tripId, 
                trip.getUserId(),
                BlockType.TEXT, 
                "行程开始", 
                buildStartText(trip),
                trip.getStartTime(),
                sortIndex++
            );
            newBlocks.add(startBlock);
        }

        // 8. 按时间顺序编排地点摘要故事块
        for (PlaceSummary place : places) {
            if (place.getStartTime() != null) {
                String title = place.getPoiName() != null ? place.getPoiName() : "地点游览";
                String content = buildPlaceSummaryText(place);
                
                StoryBlock placeBlock = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.PLACE_SUMMARY,
                    title,
                    content,
                    place.getStartTime(),
                    sortIndex++
                );
                // 设置引用 ID
                placeBlock.setRefType("PLACE_SUMMARY");
                placeBlock.setRefId(place.getId());
                // 设置封面
                if (place.getPhotoCoverId() != null) {
                    placeBlock.setCoverObjectKey("photo/" + place.getPhotoCoverId());
                }
                newBlocks.add(placeBlock);
            }
        }

        // 9. 插入照片故事块（每个地点选一张代表照片）
        for (PlaceSummary place : places) {
            List<Photo> placePhotos = photos.stream()
                    .filter(p -> place.getId() != null) // 这里可以根据实际业务逻辑过滤
                    .limit(1)
                    .collect(Collectors.toList());
            
            for (Photo photo : placePhotos) {
                if (photo.getShotTimeExif() != null) {
                    StoryBlock photoBlock = createStoryBlock(
                        tripId,
                        trip.getUserId(),
                        BlockType.PHOTO,
                        "照片记录",
                        photo.getUserCaption() != null ? photo.getUserCaption() : "美好的瞬间",
                        photo.getShotTimeExif(),
                        sortIndex++
                    );
                    photoBlock.setRefType("PHOTO");
                    photoBlock.setRefId(photo.getId());
                    photoBlock.setCoverObjectKey(photo.getObjectKey());
                    newBlocks.add(photoBlock);
                }
            }
        }

        // 10. 插入视频故事块
        for (Video video : videos) {
            if (video.getShotTimeExif() != null) {
                StoryBlock videoBlock = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.VIDEO,
                    "视频记录",
                    video.getUserCaption() != null ? video.getUserCaption() : "精彩的视频",
                    video.getShotTimeExif(),
                    sortIndex++
                );
                videoBlock.setRefType("VIDEO");
                videoBlock.setRefId(video.getId());
                videoBlock.setCoverObjectKey(video.getThumbnailObjectKey());
                newBlocks.add(videoBlock);
            }
        }

        // 11. 插入旅程笔记故事块
        for (TripNote note : notes) {
            if (note.getCreatedAt() != null) {
                StoryBlock noteBlock = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.TEXT,
                    note.getTitle() != null ? note.getTitle() : "旅程笔记",
                    note.getContent(),
                    note.getCreatedAt(),
                    sortIndex++
                );
                noteBlock.setRefType("TRIP_NOTE");
                noteBlock.setRefId(note.getId());
                newBlocks.add(noteBlock);
            }
        }

        // 12. 创建行程结束故事块
        if (trip.getEndTime() != null) {
            StoryBlock endBlock = createStoryBlock(
                tripId,
                trip.getUserId(),
                BlockType.TEXT,
                "行程结束",
                buildEndText(trip),
                trip.getEndTime(),
                sortIndex++
            );
            newBlocks.add(endBlock);
        }

        // 13. 按排序时间排序
        newBlocks.sort(Comparator.comparing(StoryBlock::getSortTime)
                .thenComparing(StoryBlock::getSortIndex));

        // 14. 批量保存
        return storyBlockRepository.saveAll(newBlocks);
    }

    @Override
    @Transactional
    public StoryBlock generateStoryBlock(Long tripId, String blockType, Map<String, Object> data) {
        // 1. 获取行程信息
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 2. 使用 AI API 生成故事块内容
        String content = aiApiClient.generateStoryBlockContent(blockType, data);

        // 3. 创建故事块
        StoryBlock storyBlock = new StoryBlock();
        storyBlock.setUserId(trip.getUserId());
        storyBlock.setTripId(tripId);
        storyBlock.setBlockType(BlockType.valueOf(blockType));
        storyBlock.setSortTime(new Date());
        storyBlock.setSortIndex(0);
        
        // 4. 根据类型设置标题和内容
        switch (BlockType.valueOf(blockType)) {
            case TEXT:
                storyBlock.setTitle("文本记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : (String) data.getOrDefault("content", ""));
                break;
            case PLACE_SUMMARY:
                storyBlock.setTitle("地点总结");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "在" + data.getOrDefault("placeName", "未知地点") + "的美好时光");
                break;
            case PHOTO:
                storyBlock.setTitle("照片记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "拍摄了美丽的照片");
                break;
            case VIDEO:
                storyBlock.setTitle("视频记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "录制了精彩的视频");
                break;
            case PHOTO_TEXT:
                storyBlock.setTitle("图文记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "图文结合的美好回忆");
                break;
            case VIDEO_TEXT:
                storyBlock.setTitle("视频文字记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "视频与文字的双重记录");
                break;
            case MIXED:
                storyBlock.setTitle("混合记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "多种形式的记录");
                break;
            case AI_SUMMARY:
                storyBlock.setTitle("AI 总结");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "AI 生成的行程总结");
                break;
            default:
                storyBlock.setTitle("其他记录");
                storyBlock.setTextContent(content != null && !content.isEmpty() ? content : "这是一条其他类型的记录");
        }
        
        return storyBlockRepository.save(storyBlock);
    }

    @Override
    public Map<String, Object> analyzeTripData(Long tripId) {
        // 1. 获取行程信息
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 2. 统计数据
        Long photoCount = photoRepository.countByTripId(tripId);
        Long videoCount = videoRepository.countByTripId(tripId);
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        List<TrackPoint> trackPoints = trackPointRepository.findByTripId(tripId);

        // 3. 构建分析结果
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("tripId", tripId);
        analysis.put("totalDistance", trip.getDistanceM() != null ? trip.getDistanceM() : 0);
        analysis.put("totalDuration", trip.getDurationSec() != null ? trip.getDurationSec() : 0);
        analysis.put("placeCount", places.size());
        analysis.put("photoCount", photoCount);
        analysis.put("videoCount", videoCount);
        analysis.put("trackPointCount", trackPoints.size());

        // 4. 深度分析
        Map<String, Object> insights = new HashMap<>();
        
        // 平均停留时间
        if (!places.isEmpty()) {
            long totalStayTime = places.stream()
                    .mapToLong(PlaceSummary::getDurationSec)
                    .sum();
            insights.put("averageStayTime", totalStayTime / places.size());
        }
        
        // 最常访问的地点
        if (!places.isEmpty()) {
            PlaceSummary longestStay = places.stream()
                    .max(Comparator.comparingLong(PlaceSummary::getDurationSec))
                    .orElse(null);
            if (longestStay != null) {
                insights.put("mostVisitedPlace", longestStay.getPoiName());
            }
        }
        
        // 旅行方式推测
        if (trip.getDistanceM() != null && trip.getDurationSec() != null) {
            double avgSpeed = (double) trip.getDistanceM() / trip.getDurationSec();
            if (avgSpeed < 1.5) {
                insights.put("travelMode", "步行为主");
            } else if (avgSpeed < 5) {
                insights.put("travelMode", "自行车/电动车");
            } else {
                insights.put("travelMode", "公共交通/驾车");
            }
        }
        
        analysis.put("insights", insights);
        
        return analysis;
    }

    @Override
    public List<String> generateTripSuggestions(Long tripId) {
        // 1. 获取行程信息
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        // 2. 获取地点信息
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        List<String> placeNames = places.stream()
                .map(PlaceSummary::getPoiName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // 3. 构建行程数据
        Map<String, Object> tripData = new HashMap<>();
        tripData.put("tripId", tripId);
        tripData.put("destination", !placeNames.isEmpty() ? placeNames.get(0).split(" ")[0] : "未知目的地");
        tripData.put("places", String.join(", ", placeNames));
        tripData.put("startTime", formatDateTime(trip.getStartTime()));
        tripData.put("type", "文化旅游");

        // 4. 调用 AI API 生成建议
        String aiSuggestions = aiApiClient.generateTripSuggestions(tripData);

        // 5. 解析 AI 生成的建议
        List<String> suggestions = new ArrayList<>();
        if (aiSuggestions != null && !aiSuggestions.isEmpty()) {
            // 按换行分割
            String[] lines = aiSuggestions.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && line.length() > 5) {
                    suggestions.add(line);
                }
            }
        }

        // 6. 如果 AI 生成失败，使用默认建议
        if (suggestions.isEmpty()) {
            suggestions.add("建议在最佳时间游览景点，避开人流高峰");
            suggestions.add("推荐尝试当地特色美食");
            suggestions.add("建议提前规划路线，节省时间");
            suggestions.add("推荐购买当地特色纪念品");
            suggestions.add("建议使用公共交通，方便又环保");
        }

        return suggestions;
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建故事块
     */
    private StoryBlock createStoryBlock(Long tripId, Long userId, BlockType blockType, 
                                        String title, String content, Date sortTime, int sortIndex) {
        StoryBlock block = new StoryBlock();
        block.setUserId(userId);
        block.setTripId(tripId);
        block.setBlockType(blockType);
        block.setTitle(title);
        block.setTextContent(content);
        block.setSortTime(sortTime);
        block.setSortIndex(sortIndex);
        block.setIsHidden(false);
        return block;
    }

    /**
     * 构建默认总结
     */
    private String buildDefaultSummary(Trip trip, List<PlaceSummary> places) {
        StringBuilder summary = new StringBuilder();
        summary.append("本次").append(trip.getTitle()).append("共游览了");
        summary.append(places.size()).append("个地点");
        
        if (trip.getDistanceM() != null && trip.getDistanceM() > 0) {
            summary.append("，总行程").append(formatDistance(trip.getDistanceM()));
        }
        
        if (trip.getDurationSec() != null && trip.getDurationSec() > 0) {
            summary.append("，耗时").append(formatDuration(trip.getDurationSec()));
        }
        
        summary.append("。留下了美好的回忆。");
        return summary.toString();
    }

    /**
     * 构建亮点列表
     */
    private List<String> buildHighlights(Trip trip, List<PlaceSummary> places, 
                                         Long photoCount, Long videoCount) {
        List<String> highlights = new ArrayList<>();
        
        // 亮点 1：游览的地点
        if (!places.isEmpty()) {
            highlights.add("游览了 " + places.size() + " 个精彩地点");
        }
        
        // 亮点 2：拍摄的照片
        if (photoCount != null && photoCount > 0) {
            highlights.add("拍摄了 " + photoCount + " 张照片");
        }
        
        // 亮点 3：录制的视频
        if (videoCount != null && videoCount > 0) {
            highlights.add("录制了 " + videoCount + " 个视频");
        }
        
        // 亮点 4：行程距离
        if (trip.getDistanceM() != null && trip.getDistanceM() > 1000) {
            highlights.add("总行程达 " + formatDistance(trip.getDistanceM()));
        }
        
        return highlights;
    }

    /**
     * 构建路线总结
     */
    private String buildRouteSummary(List<PlaceSummary> places) {
        if (places.isEmpty()) {
            return "行程路线信息不足";
        }
        
        List<String> placeNames = places.stream()
                .map(PlaceSummary::getPoiName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        return "游览路线：" + String.join(" → ", placeNames);
    }

    /**
     * 构建最佳时刻
     */
    private String buildBestMoment(List<PlaceSummary> places, Long photoCount) {
        if (places.isEmpty()) {
            return "行程中的每个瞬间都值得珍藏";
        }
        
        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong(PlaceSummary::getDurationSec))
                .orElse(null);
        
        if (longestStay != null && longestStay.getPoiName() != null) {
            return "在 " + longestStay.getPoiName() + " 度过了最美好的时光";
        }
        
        return "行程中的每个瞬间都值得珍藏";
    }

    /**
     * 构建行程开始文本
     */
    private String buildStartText(Trip trip) {
        return "今天开始了" + trip.getTitle() + "，期待一段美好的旅程！";
    }

    /**
     * 构建行程结束文本
     */
    private String buildEndText(Trip trip) {
        return trip.getTitle() + "圆满结束，收获了满满的回忆！";
    }

    /**
     * 构建地点摘要文本
     */
    private String buildPlaceSummaryText(PlaceSummary place) {
        StringBuilder text = new StringBuilder();
        
        if (place.getPoiName() != null) {
            text.append("在").append(place.getPoiName());
        } else {
            text.append("在这个地方");
        }
        
        if (place.getDurationSec() != null && place.getDurationSec() > 0) {
            text.append("停留了").append(formatDuration(place.getDurationSec()));
        }
        
        if (place.getPhotoCount() != null && place.getPhotoCount() > 0) {
            text.append("，拍摄了").append(place.getPhotoCount()).append("张照片");
        }
        
        if (place.getVideoCount() != null && place.getVideoCount() > 0) {
            text.append("，录制了").append(place.getVideoCount()).append("个视频");
        }
        
        text.append("。");
        return text.toString();
    }

    /**
     * 保存 AI 总结到数据库
     */
    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary) {
        // 先删除旧的总结
        tripAiSummaryRepository.deleteByTripId(tripId);
        
        // 创建新的总结
        TripAiSummary aiSummary = new TripAiSummary();
        aiSummary.setUserId(userId);
        aiSummary.setTripId(tripId);
        aiSummary.setOverview((String) summary.get("overview"));
        aiSummary.setRouteSummary((String) summary.get("routeSummary"));
        aiSummary.setBestMoment((String) summary.get("bestMoment"));
        
        // 处理亮点列表
        @SuppressWarnings("unchecked")
        List<String> highlights = (List<String>) summary.get("highlights");
        String highlightsText = String.join("\n", highlights);
        if (highlights != null) {
            aiSummary.setHighlights(highlightsText);
        }
        
        aiSummary.setModelName("AI Travel Assistant");
        aiSummary.setVersion((String) summary.get("version"));
        aiSummary.setGeneratedAt((Date) summary.get("generatedAt"));
        
        tripAiSummaryRepository.save(aiSummary);
    }

    /**
     * 格式化距离
     */
    private String formatDistance(Long meters) {
        if (meters == null || meters <= 0) {
            return "0 m";
        }
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000.0);
        }
        return meters + " m";
    }

    /**
     * 格式化时长
     */
    private String formatDuration(Long seconds) {
        if (seconds == null || seconds <= 0) {
            return "0 分钟";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        
        if (hours > 0) {
            return hours + "小时" + minutes + "分钟";
        }
        return minutes + "分钟";
    }

    /**
     * 格式化日期时间
     */
    private String formatDateTime(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(date);
    }
}
