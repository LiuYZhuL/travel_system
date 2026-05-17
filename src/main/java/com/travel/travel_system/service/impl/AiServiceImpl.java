package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.StoryBlock;
import com.travel.travel_system.model.TrackPoint;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.TripAiSummary;
import com.travel.travel_system.model.TripNote;
import com.travel.travel_system.model.Video;
import com.travel.travel_system.model.enums.BlockType;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.PlaceSummaryRepository;
import com.travel.travel_system.repository.StoryBlockRepository;
import com.travel.travel_system.repository.TrackPointRepository;
import com.travel.travel_system.repository.TripAiSummaryRepository;
import com.travel.travel_system.repository.TripNoteRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.AiService;
import com.travel.travel_system.utils.AiApiClient;
import com.travel.travel_system.utils.DateTimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AiServiceImpl implements AiService {

    private static final int TRIP_SUMMARY_SNAPSHOT_MAX_LENGTH = 500;

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
        return generateTripSummary(tripId, null);
    }

    @Override
    @Transactional
    public Map<String, Object> generateTripSummary(Long tripId, String userPrompt) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));

        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        Long photoCount = photoRepository.countByTripId(tripId);
        Long videoCount = videoRepository.countByTripId(tripId);
        Long noteCount = tripNoteRepository.countByTripId(tripId);
        Long storyBlockCount = storyBlockRepository.countByTripId(tripId);
        Long trackPointCount = trackPointRepository.countByTripId(tripId);
        List<TripNote> notes = tripNoteRepository.findByTripIdOrderByCreatedAtDesc(tripId);
        List<StoryBlock> storyBlocks = storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);

        if (isTripSummaryDataInsufficient(places, photoCount, videoCount, noteCount, trackPointCount)) {
            Map<String, Object> summary = buildInsufficientDataSummary(tripId, userPrompt);
            saveTripAiSummary(tripId, trip.getUserId(), summary, null, asString(summary.get("userPrompt")));
            return summary;
        }

        Map<String, Object> tripData = new HashMap<>();
        tripData.put("tripId", tripId);
        tripData.put("title", trip.getTitle());
        tripData.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
        tripData.put("endTime", trip.getEndTime() != null ? DateTimeUtils.formatDateTime(trip.getEndTime()) : null);
        tripData.put("distanceText", formatDistance(trip.getDistanceM()));
        tripData.put("durationText", DateTimeUtils.formatDuration(trip.getDurationSec()));
        tripData.put("places", places.stream()
                .map(PlaceSummary::getPoiName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(", ")));
        tripData.put("photoCount", photoCount == null ? 0L : photoCount);
        tripData.put("videoCount", videoCount == null ? 0L : videoCount);
        tripData.put("noteCount", noteCount == null ? 0L : noteCount);
        tripData.put("storyBlockCount", storyBlockCount == null ? 0L : storyBlockCount);
        tripData.put("trackPointCount", trackPointCount == null ? 0L : trackPointCount);
        tripData.put("notes", summarizeNotes(notes));
        tripData.put("storyBlocks", summarizeStoryBlocks(storyBlocks));

        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()))
                .orElse(null);
        if (longestStay != null) {
            tripData.put("longestStayPlace", longestStay.getPoiName());
            tripData.put("longestStayDuration", DateTimeUtils.formatDuration(longestStay.getDurationSec()));
        }

        String effectivePrompt = (userPrompt != null && !userPrompt.isBlank()) ? userPrompt.trim() : null;
        String aiSummary = aiApiClient.generateTripSummary(tripData, effectivePrompt);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tripId", tripId);
        summary.put("overview", normalizeOverview(aiSummary, trip, places));
        summary.put("highlights", buildHighlights(trip, places, photoCount, videoCount));
        summary.put("routeSummary", buildRouteSummary(places));
        summary.put("bestMoment", buildBestMoment(places, photoCount));
        summary.put("generatedAt", new Date());
        summary.put("version", "v1.0");
        summary.put("userPrompt", effectivePrompt);
        summary.put("dataSufficient", true);

        saveTripAiSummary(tripId, trip.getUserId(), summary, null, effectivePrompt);
        return summary;
    }

    private boolean isTripSummaryDataInsufficient(List<PlaceSummary> places,
                                                  Long photoCount,
                                                  Long videoCount,
                                                  Long noteCount,
                                                  Long trackPointCount) {
        return (places == null || places.isEmpty())
                && (photoCount == null || photoCount == 0)
                && (videoCount == null || videoCount == 0)
                && (noteCount == null || noteCount == 0)
                && (trackPointCount == null || trackPointCount < 3);
    }

    private Map<String, Object> buildInsufficientDataSummary(Long tripId, String userPrompt) {
        String effectivePrompt = (userPrompt != null && !userPrompt.isBlank()) ? userPrompt.trim() : null;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tripId", tripId);
        summary.put("overview", "当前行程记录数据较少，暂无法生成具有参考价值的旅行总结。建议补充轨迹、地点、照片或笔记后重新生成。");
        summary.put("highlights", Collections.emptyList());
        summary.put("routeSummary", "行程数据不足，暂未形成可总结的路线内容。");
        summary.put("bestMoment", "补充旅行记录后，系统将重新识别值得回看的片段。");
        summary.put("generatedAt", new Date());
        summary.put("version", "v1.0");
        summary.put("userPrompt", effectivePrompt);
        summary.put("dataSufficient", false);
        return summary;
    }

    private String summarizeNotes(List<TripNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return "";
        }
        return notes.stream()
                .filter(Objects::nonNull)
                .limit(3)
                .map(note -> {
                    String title = note.getTitle() == null ? "" : note.getTitle().trim();
                    String content = note.getContent() == null ? "" : note.getContent().trim();
                    if (!title.isEmpty() && !content.isEmpty()) {
                        return title + "：" + content;
                    }
                    return !content.isEmpty() ? content : title;
                })
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("；"));
    }

    private String summarizeStoryBlocks(List<StoryBlock> storyBlocks) {
        if (storyBlocks == null || storyBlocks.isEmpty()) {
            return "";
        }
        return storyBlocks.stream()
                .filter(Objects::nonNull)
                .filter(block -> !Boolean.TRUE.equals(block.getIsHidden()))
                .limit(5)
                .map(block -> {
                    String title = block.getTitle() == null ? "" : block.getTitle().trim();
                    String content = block.getTextContent() == null ? "" : block.getTextContent().trim();
                    if (!title.isEmpty() && !content.isEmpty()) {
                        return title + "：" + content;
                    }
                    return !content.isEmpty() ? content : title;
                })
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("；"));
    }

    @Override
    @Transactional
    public List<StoryBlock> rebuildStoryBlocks(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));

        List<StoryBlock> oldBlocks = storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);
        storyBlockRepository.deleteAll(oldBlocks);

        List<StoryBlock> newBlocks = new ArrayList<>();
        int sortIndex = 0;

        if (trip.getStartTime() != null) {
            newBlocks.add(createStoryBlock(tripId, trip.getUserId(), BlockType.TEXT, "Trip Start", buildStartText(trip), trip.getStartTime(), sortIndex++));
        }

        for (PlaceSummary place : placeSummaryRepository.findByTripId(tripId)) {
            if (place.getStartTime() == null) {
                continue;
            }
            StoryBlock block = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.PLACE_SUMMARY,
                    place.getPoiName() == null || place.getPoiName().isBlank() ? "Place Stop" : place.getPoiName(),
                    buildPlaceSummaryText(place),
                    place.getStartTime(),
                    sortIndex++);
            block.setRefType("PLACE_SUMMARY");
            block.setRefId(place.getId());
            if (place.getPhotoCoverId() != null) {
                Photo photo = photoRepository.findById(place.getPhotoCoverId()).orElse(null);
                if (photo != null) {
                    block.setCoverObjectKey(photo.getObjectKey());
                }
            }
            newBlocks.add(block);
        }

        for (Photo photo : photoRepository.findByTripId(tripId)) {
            Date sortTime = photo.getShotTimeExif() != null ? photo.getShotTimeExif() : photo.getCreatedAt();
            if (sortTime == null) {
                continue;
            }
            StoryBlock block = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.PHOTO,
                    "Photo",
                    photo.getUserCaption() == null || photo.getUserCaption().isBlank() ? "Captured a travel moment." : photo.getUserCaption(),
                    sortTime,
                    sortIndex++);
            block.setRefType("PHOTO");
            block.setRefId(photo.getId());
            block.setCoverObjectKey(photo.getObjectKey());
            newBlocks.add(block);
        }

        for (Video video : videoRepository.findByTripId(tripId)) {
            Date sortTime = video.getShotTimeExif() != null ? video.getShotTimeExif() : video.getCreatedAt();
            if (sortTime == null) {
                continue;
            }
            StoryBlock block = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.VIDEO,
                    "Video",
                    video.getUserCaption() == null || video.getUserCaption().isBlank() ? "Captured a moving travel clip." : video.getUserCaption(),
                    sortTime,
                    sortIndex++);
            block.setRefType("VIDEO");
            block.setRefId(video.getId());
            block.setCoverObjectKey(video.getThumbnailObjectKey());
            newBlocks.add(block);
        }

        for (TripNote note : tripNoteRepository.findByTripIdOrderByCreatedAtDesc(tripId)) {
            if (note.getCreatedAt() == null) {
                continue;
            }
            StoryBlock block = createStoryBlock(
                    tripId,
                    trip.getUserId(),
                    BlockType.TEXT,
                    note.getTitle() == null || note.getTitle().isBlank() ? "Note" : note.getTitle(),
                    note.getContent(),
                    note.getCreatedAt(),
                    sortIndex++);
            block.setRefType("TRIP_NOTE");
            block.setRefId(note.getId());
            newBlocks.add(block);
        }

        if (trip.getEndTime() != null) {
            newBlocks.add(createStoryBlock(tripId, trip.getUserId(), BlockType.TEXT, "Trip End", buildEndText(trip), trip.getEndTime(), sortIndex));
        }

        newBlocks.sort(Comparator.comparing(StoryBlock::getSortTime).thenComparing(StoryBlock::getSortIndex));
        return storyBlockRepository.saveAll(newBlocks);
    }

    @Override
    @Transactional
    public StoryBlock generateStoryBlock(Long tripId, String blockType, Map<String, Object> data) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));
        BlockType type = BlockType.valueOf(blockType);
        String content = aiApiClient.generateStoryBlockContent(blockType, data);
        if (content == null || content.isBlank()) {
            content = String.valueOf(data.getOrDefault("content", ""));
        }
        StoryBlock block = createStoryBlock(tripId, trip.getUserId(), type, resolveBlockTitle(type), content, new Date(), 0);
        return storyBlockRepository.save(block);
    }

    @Override
    public Map<String, Object> analyzeTripData(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));
        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        List<TrackPoint> trackPoints = trackPointRepository.findByTripId(tripId);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("tripId", tripId);
        analysis.put("totalDistance", trip.getDistanceM() != null ? trip.getDistanceM() : 0L);
        analysis.put("totalDuration", trip.getDurationSec() != null ? trip.getDurationSec() : 0L);
        analysis.put("placeCount", places.size());
        analysis.put("photoCount", photoRepository.countByTripId(tripId));
        analysis.put("videoCount", videoRepository.countByTripId(tripId));
        analysis.put("trackPointCount", trackPoints.size());

        Map<String, Object> insights = new LinkedHashMap<>();
        if (!places.isEmpty()) {
            insights.put("longestStayPlace", places.stream()
                    .max(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()))
                    .map(PlaceSummary::getPoiName)
                    .orElse(null));
        }
        if (trip.getDistanceM() != null && trip.getDurationSec() != null && trip.getDurationSec() > 0) {
            double avgSpeed = (double) trip.getDistanceM() / trip.getDurationSec();
            if (avgSpeed < 1.5) {
                insights.put("travelMode", "walk");
            } else if (avgSpeed < 5) {
                insights.put("travelMode", "slow_move");
            } else {
                insights.put("travelMode", "transit_or_drive");
            }
        }
        analysis.put("insights", insights);
        return analysis;
    }

    @Override
    public List<String> generateTripSuggestions(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));
        List<String> placeNames = placeSummaryRepository.findByTripId(tripId).stream()
                .map(PlaceSummary::getPoiName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        Map<String, Object> tripData = new LinkedHashMap<>();
        tripData.put("destination", placeNames.isEmpty() ? "unknown" : placeNames.get(0));
        tripData.put("places", String.join(", ", placeNames));
        tripData.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
        tripData.put("type", "free_trip");

        String aiSuggestions = aiApiClient.generateTripSuggestions(tripData);
        List<String> suggestions = new ArrayList<>();
        if (aiSuggestions != null && !aiSuggestions.isBlank()) {
            Arrays.stream(aiSuggestions.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(suggestions::add);
        }
        if (suggestions.isEmpty()) {
            suggestions.add("Review the longest-stay place first.");
            suggestions.add("Add more captions to media for better reports.");
            suggestions.add("Plan long-distance moves in advance.");
        }
        return suggestions;
    }

    @Override
    @Transactional
    public Map<String, Object> regenerateTripSummary(Long tripId, String reason) {
        return regenerateTripSummary(tripId, reason, null);
    }

    @Override
    @Transactional
    public Map<String, Object> regenerateTripSummary(Long tripId, String reason, String userPrompt) {
        Map<String, Object> summary = generateTripSummary(tripId, userPrompt);
        String finalReason = reason != null ? reason : "manual_regenerate";
        tripAiSummaryRepository.findFirstByTripIdAndIsLatestTrueOrderByGeneratedAtDescIdDesc(tripId).ifPresent(latest -> {
            latest.setRegenerateReason(finalReason);
            tripAiSummaryRepository.save(latest);
        });
        return summary;
    }

    @Override
    public List<Map<String, Object>> getAiSummaryHistory(Long tripId) {
        return tripAiSummaryRepository.findByTripIdOrderByGeneratedAtDesc(tripId).stream().map(summary -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", summary.getId());
            item.put("overview", summary.getOverview());
            item.put("highlights", parseHighlights(summary.getHighlights()));
            item.put("routeSummary", summary.getRouteSummary());
            item.put("bestMoment", summary.getBestMoment());
            item.put("isLatest", summary.getIsLatest());
            item.put("regenerateReason", summary.getRegenerateReason());
            item.put("generatedAt", DateTimeUtils.formatDateTime(summary.getGeneratedAt()));
            item.put("version", summary.getVersion());
            item.put("userPrompt", summary.getUserPrompt());
            return item;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public Map<String, Object> rollbackAiSummary(Long tripId, Long summaryId) {
        TripAiSummary target = tripAiSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new RuntimeException("AI summary not found, id: " + summaryId));
        if (!tripId.equals(target.getTripId())) {
            throw new RuntimeException("AI summary does not belong to this trip");
        }

        List<TripAiSummary> all = tripAiSummaryRepository.findByTripIdOrderByGeneratedAtDesc(tripId);
        for (TripAiSummary item : all) {
            item.setIsLatest(false);
        }
        tripAiSummaryRepository.saveAll(all);

        target.setIsLatest(true);
        target.setRegenerateReason("rollback_" + target.getId());
        tripAiSummaryRepository.save(target);

        tripRepository.findById(tripId).ifPresent(trip -> {
            trip.setSummaryText(buildTripSummarySnapshot(target.getOverview()));
            trip.setGeneratedAt(target.getGeneratedAt());
            tripRepository.save(trip);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.getId());
        result.put("overview", target.getOverview());
        result.put("highlights", parseHighlights(target.getHighlights()));
        result.put("routeSummary", target.getRouteSummary());
        result.put("bestMoment", target.getBestMoment());
        result.put("isLatest", true);
        result.put("generatedAt", DateTimeUtils.formatDateTime(target.getGeneratedAt()));
        result.put("userPrompt", target.getUserPrompt());
        return result;
    }

    @Override
    @Transactional
    public void deleteAiSummary(Long tripId) {
        tripAiSummaryRepository.deleteByTripId(tripId);
        tripRepository.findById(tripId).ifPresent(trip -> {
            trip.setSummaryText(null);
            trip.setGeneratedAt(null);
            tripRepository.save(trip);
        });
    }

    @Override
    @Transactional
    public void deleteAiSummaryVersion(Long tripId, Long summaryId) {
        TripAiSummary target = tripAiSummaryRepository.findById(summaryId)
                .orElseThrow(() -> new RuntimeException("AI 总结版本不存在: " + summaryId));
        if (!target.getTripId().equals(tripId)) {
            throw new RuntimeException("summaryId 不属于该行程");
        }

        boolean wasLatest = Boolean.TRUE.equals(target.getIsLatest());
        tripAiSummaryRepository.delete(target);

        if (wasLatest) {
            // 晋升次新版为最新版
            tripAiSummaryRepository.findByTripIdOrderByGeneratedAtDesc(tripId).stream()
                    .findFirst()
                    .ifPresentOrElse(
                            next -> {
                                next.setIsLatest(true);
                                tripAiSummaryRepository.save(next);
                                tripRepository.findById(tripId).ifPresent(trip -> {
                                    trip.setSummaryText(buildTripSummarySnapshot(next.getOverview()));
                                    trip.setGeneratedAt(next.getGeneratedAt());
                                    tripRepository.save(trip);
                                });
                            },
                            () -> tripRepository.findById(tripId).ifPresent(trip -> {
                                trip.setSummaryText(null);
                                trip.setGeneratedAt(null);
                                tripRepository.save(trip);
                            })
                    );
        }
    }

    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary) {
        saveTripAiSummary(tripId, userId, summary, null, null);
    }

    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary, String regenerateReason) {
        saveTripAiSummary(tripId, userId, summary, regenerateReason, null);
    }

    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary,
                                  String regenerateReason, String userPrompt) {
        List<TripAiSummary> existing = tripAiSummaryRepository.findByTripIdOrderByGeneratedAtDesc(tripId);
        for (TripAiSummary item : existing) {
            item.setIsLatest(false);
        }
        tripAiSummaryRepository.saveAll(existing);

        TripAiSummary aiSummary = new TripAiSummary();
        aiSummary.setUserId(userId);
        aiSummary.setTripId(tripId);
        aiSummary.setOverview(asString(summary.get("overview")));
        aiSummary.setRouteSummary(asString(summary.get("routeSummary")));
        aiSummary.setBestMoment(asString(summary.get("bestMoment")));
        Object highlightsObj = summary.get("highlights");
        if (highlightsObj instanceof List<?> list) {
            aiSummary.setHighlights(list.stream().map(String::valueOf).collect(Collectors.joining("\n")));
        }
        aiSummary.setModelName("AI Travel Assistant");
        aiSummary.setVersion(asString(summary.get("version")));
        aiSummary.setGeneratedAt(summary.get("generatedAt") instanceof Date date ? date : new Date());
        aiSummary.setIsLatest(true);
        aiSummary.setRegenerateReason(regenerateReason);
        aiSummary.setUserPrompt(userPrompt);
        tripAiSummaryRepository.save(aiSummary);

        tripRepository.findById(tripId).ifPresent(trip -> {
            trip.setSummaryText(buildTripSummarySnapshot(aiSummary.getOverview()));
            trip.setGeneratedAt(aiSummary.getGeneratedAt());
            tripRepository.save(trip);
        });
    }

    private StoryBlock createStoryBlock(Long tripId, Long userId, BlockType blockType,
                                        String title, String content, Date sortTime, int sortIndex) {
        StoryBlock block = new StoryBlock();
        block.setTripId(tripId);
        block.setUserId(userId);
        block.setBlockType(blockType);
        block.setTitle(title);
        block.setTextContent(content);
        block.setSortTime(sortTime);
        block.setSortIndex(sortIndex);
        block.setIsHidden(false);
        return block;
    }

    private String resolveBlockTitle(BlockType type) {
        return switch (type) {
            case PLACE_SUMMARY -> "Place Summary";
            case PHOTO -> "Photo";
            case VIDEO -> "Video";
            case AI_SUMMARY -> "AI Summary";
            default -> "Text";
        };
    }

    private String buildDefaultSummary(Trip trip, List<PlaceSummary> places) {
        String title = trip.getTitle() == null || trip.getTitle().isBlank() ? "旅程" : trip.getTitle().trim();
        List<String> placeNames = collectPlaceNames(places, 3);
        List<String> sceneTags = collectSemanticTags(places, 3);
        StringBuilder summary = new StringBuilder();
        summary.append("这次").append(title).append("共串联了 ").append(places.size()).append(" 个停留点");
        if (!placeNames.isEmpty()) {
            summary.append("，主要经过 ").append(String.join("、", placeNames));
        }
        if (!sceneTags.isEmpty()) {
            summary.append("，涵盖了 ").append(String.join("、", sceneTags)).append(" 等场景");
        }
        if (trip.getDistanceM() != null && trip.getDistanceM() > 0) {
            summary.append("，累计行程 ").append(formatDistance(trip.getDistanceM()));
        }
        if (trip.getDurationSec() != null && trip.getDurationSec() > 0) {
            summary.append("，用时 ").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
        }
        summary.append("。");
        return summary.toString();
    }

    private List<String> buildHighlights(Trip trip, List<PlaceSummary> places, Long photoCount, Long videoCount) {
        LinkedHashSet<String> highlights = new LinkedHashSet<>();
        if (!places.isEmpty()) {
            highlights.add("共识别出 " + places.size() + " 个有效停留点。");
        }

        List<String> topPlaces = collectPlaceNames(
                places.stream()
                        .sorted(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()).reversed())
                        .collect(Collectors.toList()),
                3
        );
        if (!topPlaces.isEmpty()) {
            highlights.add("主要停留地点包括 " + String.join("、", topPlaces) + "。");
        }

        List<String> sceneTags = collectSemanticTags(places, 4);
        if (!sceneTags.isEmpty()) {
            highlights.add("行程覆盖了 " + String.join("、", sceneTags) + " 等场景。");
        }

        if ((photoCount != null && photoCount > 0) || (videoCount != null && videoCount > 0)) {
            StringBuilder mediaHighlight = new StringBuilder("影像记录共包含 ");
            boolean appended = false;
            if (photoCount != null && photoCount > 0) {
                mediaHighlight.append(photoCount).append(" 张照片");
                appended = true;
            }
            if (videoCount != null && videoCount > 0) {
                if (appended) {
                    mediaHighlight.append("、");
                }
                mediaHighlight.append(videoCount).append(" 个视频");
            }
            mediaHighlight.append("。");
            highlights.add(mediaHighlight.toString());
        }

        if ((trip.getDistanceM() != null && trip.getDistanceM() > 0) || (trip.getDurationSec() != null && trip.getDurationSec() > 0)) {
            StringBuilder travelStats = new StringBuilder("行程统计：");
            boolean appended = false;
            if (trip.getDistanceM() != null && trip.getDistanceM() > 0) {
                travelStats.append("距离 ").append(formatDistance(trip.getDistanceM()));
                appended = true;
            }
            if (trip.getDurationSec() != null && trip.getDurationSec() > 0) {
                if (appended) {
                    travelStats.append("，");
                }
                travelStats.append("用时 ").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
            }
            travelStats.append("。");
            highlights.add(travelStats.toString());
        }

        return new ArrayList<>(highlights);
    }

    private String buildRouteSummary(List<PlaceSummary> places) {
        if (places.isEmpty()) {
            return "暂未识别出明确的停留路线。";
        }
        List<String> placeNames = collectPlaceNames(places, 6);
        if (!placeNames.isEmpty()) {
            return "路线大致为：" + String.join(" → ", placeNames) + "。";
        }
        List<String> sceneTags = collectSemanticTags(places, 4);
        if (!sceneTags.isEmpty()) {
            return "当前已识别出 " + String.join("、", sceneTags) + " 等场景，具体地点名称仍在补充中。";
        }
        return "轨迹已记录，但地点语义信息仍在补充。";
    }

    private String buildBestMoment(List<PlaceSummary> places, Long photoCount) {
        if (places.isEmpty()) {
            return "旅途中每一次停留都值得回看。";
        }
        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()))
                .orElse(null);
        if (longestStay != null) {
            String placeName = isMeaningfulPlaceName(longestStay.getPoiName())
                    ? longestStay.getPoiName().trim()
                    : "这个停留点";
            String durationText = longestStay.getDurationSec() == null || longestStay.getDurationSec() <= 0
                    ? null
                    : DateTimeUtils.formatDuration(longestStay.getDurationSec());
            String semanticLabel = resolvePrimarySemanticTag(longestStay);
            if (durationText != null && semanticLabel != null) {
                return "在 " + placeName + " 停留了 " + durationText + "，这段 " + semanticLabel + " 场景成为整段行程里最有记忆点的片段。";
            }
            if (durationText != null) {
                return "在 " + placeName + " 停留了 " + durationText + "，是这段行程最值得回味的片段。";
            }
            return placeName + " 是这段旅程里最容易被记住的一站。";
        }
        if (photoCount != null && photoCount > 0) {
            return "影像记录最密集的那段停留，构成了这次行程最鲜明的记忆点。";
        }
        return "旅途中每一次停留都值得回看。";
    }

    private String buildStartText(Trip trip) {
        return "行程从这里开始，新的轨迹和记录正在展开。";
    }

    private String buildEndText(Trip trip) {
        String title = trip.getTitle() == null || trip.getTitle().isBlank() ? "这段旅程" : trip.getTitle().trim();
        return title + " 已顺利结束，轨迹、停留与影像记录已整理完成。";
    }

    private String buildPlaceSummaryText(PlaceSummary place) {
        List<String> parts = new ArrayList<>();
        if (isMeaningfulPlaceName(place.getPoiName())) {
            parts.add("停留地点：" + place.getPoiName().trim());
        }
        if (place.getDurationSec() != null && place.getDurationSec() > 0) {
            parts.add("停留时长：" + DateTimeUtils.formatDuration(place.getDurationSec()));
        }
        List<String> tags = collectSemanticTags(Collections.singletonList(place), 3);
        if (!tags.isEmpty()) {
            parts.add("场景标签：" + String.join("、", tags));
        }
        if ((place.getPhotoCount() != null && place.getPhotoCount() > 0) || (place.getVideoCount() != null && place.getVideoCount() > 0)) {
            StringBuilder media = new StringBuilder("影像记录：");
            boolean appended = false;
            if (place.getPhotoCount() != null && place.getPhotoCount() > 0) {
                media.append(place.getPhotoCount()).append(" 张照片");
                appended = true;
            }
            if (place.getVideoCount() != null && place.getVideoCount() > 0) {
                if (appended) {
                    media.append("、");
                }
                media.append(place.getVideoCount()).append(" 个视频");
            }
            parts.add(media.toString());
        }
        return parts.isEmpty() ? "这里留下了一段值得回看的停留。" : String.join("，", parts) + "。";
    }

    private List<String> collectPlaceNames(List<PlaceSummary> places, int limit) {
        return places.stream()
                .map(PlaceSummary::getPoiName)
                .filter(this::isMeaningfulPlaceName)
                .map(String::trim)
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<String> collectSemanticTags(List<PlaceSummary> places, int limit) {
        return places.stream()
                .map(PlaceSummary::getUserTags)
                .filter(Objects::nonNull)
                .flatMap(tags -> Arrays.stream(tags.split(",")))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private String resolvePrimarySemanticTag(PlaceSummary place) {
        return collectSemanticTags(Collections.singletonList(place), 1).stream().findFirst().orElse(null);
    }

    private boolean isMeaningfulPlaceName(String placeName) {
        if (placeName == null || placeName.isBlank()) {
            return false;
        }
        return !placeName.trim().matches("(?i)^(地点|停留点|place)\\s*\\d+$");
    }

    private String formatDistance(Long meters) {
        if (meters == null || meters <= 0) {
            return "0 m";
        }
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000.0);
        }
        return meters + " m";
    }

    private String buildTripSummarySnapshot(String overview) {
        if (overview == null) {
            return null;
        }
        String normalized = overview.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= TRIP_SUMMARY_SNAPSHOT_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, TRIP_SUMMARY_SNAPSHOT_MAX_LENGTH);
    }

    private String normalizeOverview(String aiSummary, Trip trip, List<PlaceSummary> places) {
        if (aiSummary == null || aiSummary.isBlank()) {
            return buildDefaultSummary(trip, places);
        }

        List<String> normalizedLines = Arrays.stream(aiSummary.replace("\r", "\n").split("\n"))
                .map(String::trim)
                .map(line -> line
                        .replace("***", "")
                        .replace("**", "")
                        .replace("__", "")
                        .replace("`", "")
                        .replaceAll("^#{1,6}\\s*", "")
                        .replaceAll("^[-*•]\\s*", "")
                        .replaceAll("^\\d+[.)、]\\s*", "")
                        .replaceAll("(?i)(trip summary|highlights|overall feeling|summary)[:：]?", "")
                        .replaceAll("(旅行总结|行程总结|总结|亮点|路线概览|最佳片段)[:：]?", "")
                        .trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());

        if (normalizedLines.isEmpty()) {
            return buildDefaultSummary(trip, places);
        }

        String normalized = String.join(" ", normalizedLines)
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return buildDefaultSummary(trip, places);
        }

        normalized = shortenOverview(normalized, 180);
        if (!normalized.endsWith("。") && !normalized.endsWith("！") && !normalized.endsWith("？")
                && !normalized.endsWith("!") && !normalized.endsWith("?")) {
            normalized = normalized + "。";
        }
        return normalized;
    }

    private String shortenOverview(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int punctuationIndex = -1;
        for (int i = Math.min(maxLength, text.length()) - 1; i >= Math.max(0, maxLength - 30); i--) {
            char ch = text.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == ',' || ch == '，') {
                punctuationIndex = i;
                break;
            }
        }
        if (punctuationIndex > 0) {
            return text.substring(0, punctuationIndex).trim();
        }
        return text.substring(0, maxLength).trim();
    }
    private List<String> parseHighlights(String highlights) {
        if (highlights == null || highlights.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(highlights.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}


