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
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("Trip not found, tripId: " + tripId));

        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        Long photoCount = photoRepository.countByTripId(tripId);
        Long videoCount = videoRepository.countByTripId(tripId);

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

        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()))
                .orElse(null);
        if (longestStay != null) {
            tripData.put("longestStayPlace", longestStay.getPoiName());
            tripData.put("longestStayDuration", DateTimeUtils.formatDuration(longestStay.getDurationSec()));
        }

        String aiSummary = aiApiClient.generateTripSummary(tripData);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tripId", tripId);
        summary.put("overview", normalizeOverview(aiSummary, trip, places));
        summary.put("highlights", buildHighlights(trip, places, photoCount, videoCount));
        summary.put("routeSummary", buildRouteSummary(places));
        summary.put("bestMoment", buildBestMoment(places, photoCount));
        summary.put("generatedAt", new Date());
        summary.put("version", "v1.0");

        saveTripAiSummary(tripId, trip.getUserId(), summary);
        return summary;
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
        Map<String, Object> summary = generateTripSummary(tripId);
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
        return result;
    }

    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary) {
        saveTripAiSummary(tripId, userId, summary, null);
    }

    @Transactional
    public void saveTripAiSummary(Long tripId, Long userId, Map<String, Object> summary, String regenerateReason) {
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
        String title = trip.getTitle() == null || trip.getTitle().isBlank() ? "\u65c5\u7a0b" : trip.getTitle().trim();
        List<String> placeNames = collectPlaceNames(places, 3);
        List<String> sceneTags = collectSemanticTags(places, 3);
        StringBuilder summary = new StringBuilder();
        summary.append("\u8fd9\u6b21").append(title).append("\u5171\u4e32\u8054\u4e86 ").append(places.size()).append(" \u4e2a\u505c\u7559\u70b9");
        if (!placeNames.isEmpty()) {
            summary.append("\uff0c\u4e3b\u8981\u7ecf\u8fc7 ").append(String.join("\u3001", placeNames));
        }
        if (!sceneTags.isEmpty()) {
            summary.append("\uff0c\u6db5\u76d6\u4e86 ").append(String.join("\u3001", sceneTags)).append(" \u7b49\u573a\u666f");
        }
        if (trip.getDistanceM() != null && trip.getDistanceM() > 0) {
            summary.append("\uff0c\u7d2f\u8ba1\u884c\u7a0b ").append(formatDistance(trip.getDistanceM()));
        }
        if (trip.getDurationSec() != null && trip.getDurationSec() > 0) {
            summary.append("\uff0c\u7528\u65f6 ").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
        }
        summary.append("\u3002");
        return summary.toString();
    }

    private List<String> buildHighlights(Trip trip, List<PlaceSummary> places, Long photoCount, Long videoCount) {
        LinkedHashSet<String> highlights = new LinkedHashSet<>();
        if (!places.isEmpty()) {
            highlights.add("\u5171\u8bc6\u522b\u51fa " + places.size() + " \u4e2a\u6709\u6548\u505c\u7559\u70b9\u3002");
        }

        List<String> topPlaces = collectPlaceNames(
                places.stream()
                        .sorted(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()).reversed())
                        .collect(Collectors.toList()),
                3
        );
        if (!topPlaces.isEmpty()) {
            highlights.add("\u4e3b\u8981\u505c\u7559\u5730\u70b9\u5305\u62ec " + String.join("\u3001", topPlaces) + "\u3002");
        }

        List<String> sceneTags = collectSemanticTags(places, 4);
        if (!sceneTags.isEmpty()) {
            highlights.add("\u884c\u7a0b\u8986\u76d6\u4e86 " + String.join("\u3001", sceneTags) + " \u7b49\u573a\u666f\u3002");
        }

        if ((photoCount != null && photoCount > 0) || (videoCount != null && videoCount > 0)) {
            StringBuilder mediaHighlight = new StringBuilder("\u5f71\u50cf\u8bb0\u5f55\u5171\u5305\u542b ");
            boolean appended = false;
            if (photoCount != null && photoCount > 0) {
                mediaHighlight.append(photoCount).append(" \u5f20\u7167\u7247");
                appended = true;
            }
            if (videoCount != null && videoCount > 0) {
                if (appended) {
                    mediaHighlight.append("\u3001");
                }
                mediaHighlight.append(videoCount).append(" \u4e2a\u89c6\u9891");
            }
            mediaHighlight.append("\u3002");
            highlights.add(mediaHighlight.toString());
        }

        if ((trip.getDistanceM() != null && trip.getDistanceM() > 0) || (trip.getDurationSec() != null && trip.getDurationSec() > 0)) {
            StringBuilder travelStats = new StringBuilder("\u884c\u7a0b\u7edf\u8ba1\uff1a");
            boolean appended = false;
            if (trip.getDistanceM() != null && trip.getDistanceM() > 0) {
                travelStats.append("\u8ddd\u79bb ").append(formatDistance(trip.getDistanceM()));
                appended = true;
            }
            if (trip.getDurationSec() != null && trip.getDurationSec() > 0) {
                if (appended) {
                    travelStats.append("\uff0c");
                }
                travelStats.append("\u7528\u65f6 ").append(DateTimeUtils.formatDuration(trip.getDurationSec()));
            }
            travelStats.append("\u3002");
            highlights.add(travelStats.toString());
        }

        return new ArrayList<>(highlights);
    }

    private String buildRouteSummary(List<PlaceSummary> places) {
        if (places.isEmpty()) {
            return "\u6682\u672a\u8bc6\u522b\u51fa\u660e\u786e\u7684\u505c\u7559\u8def\u7ebf\u3002";
        }
        List<String> placeNames = collectPlaceNames(places, 6);
        if (!placeNames.isEmpty()) {
            return "\u8def\u7ebf\u5927\u81f4\u4e3a\uff1a" + String.join(" \u2192 ", placeNames) + "\u3002";
        }
        List<String> sceneTags = collectSemanticTags(places, 4);
        if (!sceneTags.isEmpty()) {
            return "\u5f53\u524d\u5df2\u8bc6\u522b\u51fa " + String.join("\u3001", sceneTags) + " \u7b49\u573a\u666f\uff0c\u5177\u4f53\u5730\u70b9\u540d\u79f0\u4ecd\u5728\u8865\u5145\u4e2d\u3002";
        }
        return "\u8f68\u8ff9\u5df2\u8bb0\u5f55\uff0c\u4f46\u5730\u70b9\u8bed\u4e49\u4fe1\u606f\u4ecd\u5728\u8865\u5145\u3002";
    }

    private String buildBestMoment(List<PlaceSummary> places, Long photoCount) {
        if (places.isEmpty()) {
            return "\u65c5\u9014\u4e2d\u6bcf\u4e00\u6b21\u505c\u7559\u90fd\u503c\u5f97\u56de\u770b\u3002";
        }
        PlaceSummary longestStay = places.stream()
                .max(Comparator.comparingLong((PlaceSummary p) -> p.getDurationSec() == null ? 0L : p.getDurationSec()))
                .orElse(null);
        if (longestStay != null) {
            String placeName = isMeaningfulPlaceName(longestStay.getPoiName())
                    ? longestStay.getPoiName().trim()
                    : "\u8fd9\u4e2a\u505c\u7559\u70b9";
            String durationText = longestStay.getDurationSec() == null || longestStay.getDurationSec() <= 0
                    ? null
                    : DateTimeUtils.formatDuration(longestStay.getDurationSec());
            String semanticLabel = resolvePrimarySemanticTag(longestStay);
            if (durationText != null && semanticLabel != null) {
                return "\u5728 " + placeName + " \u505c\u7559\u4e86 " + durationText + "\uff0c\u8fd9\u6bb5 " + semanticLabel + " \u573a\u666f\u6210\u4e3a\u6574\u6bb5\u884c\u7a0b\u91cc\u6700\u6709\u8bb0\u5fc6\u70b9\u7684\u7247\u6bb5\u3002";
            }
            if (durationText != null) {
                return "\u5728 " + placeName + " \u505c\u7559\u4e86 " + durationText + "\uff0c\u662f\u8fd9\u6bb5\u884c\u7a0b\u6700\u503c\u5f97\u56de\u5473\u7684\u7247\u6bb5\u3002";
            }
            return placeName + " \u662f\u8fd9\u6bb5\u65c5\u7a0b\u91cc\u6700\u5bb9\u6613\u88ab\u8bb0\u4f4f\u7684\u4e00\u7ad9\u3002";
        }
        if (photoCount != null && photoCount > 0) {
            return "\u5f71\u50cf\u8bb0\u5f55\u6700\u5bc6\u96c6\u7684\u90a3\u6bb5\u505c\u7559\uff0c\u6784\u6210\u4e86\u8fd9\u6b21\u884c\u7a0b\u6700\u9c9c\u660e\u7684\u8bb0\u5fc6\u70b9\u3002";
        }
        return "\u65c5\u9014\u4e2d\u6bcf\u4e00\u6b21\u505c\u7559\u90fd\u503c\u5f97\u56de\u770b\u3002";
    }

    private String buildStartText(Trip trip) {
        return "\u884c\u7a0b\u4ece\u8fd9\u91cc\u5f00\u59cb\uff0c\u65b0\u7684\u8f68\u8ff9\u548c\u8bb0\u5f55\u6b63\u5728\u5c55\u5f00\u3002";
    }

    private String buildEndText(Trip trip) {
        String title = trip.getTitle() == null || trip.getTitle().isBlank() ? "\u8fd9\u6bb5\u65c5\u7a0b" : trip.getTitle().trim();
        return title + " \u5df2\u987a\u5229\u7ed3\u675f\uff0c\u8f68\u8ff9\u3001\u505c\u7559\u4e0e\u5f71\u50cf\u8bb0\u5f55\u5df2\u6574\u7406\u5b8c\u6210\u3002";
    }

    private String buildPlaceSummaryText(PlaceSummary place) {
        List<String> parts = new ArrayList<>();
        if (isMeaningfulPlaceName(place.getPoiName())) {
            parts.add("\u505c\u7559\u5730\u70b9\uff1a" + place.getPoiName().trim());
        }
        if (place.getDurationSec() != null && place.getDurationSec() > 0) {
            parts.add("\u505c\u7559\u65f6\u957f\uff1a" + DateTimeUtils.formatDuration(place.getDurationSec()));
        }
        List<String> tags = collectSemanticTags(Collections.singletonList(place), 3);
        if (!tags.isEmpty()) {
            parts.add("\u573a\u666f\u6807\u7b7e\uff1a" + String.join("\u3001", tags));
        }
        if ((place.getPhotoCount() != null && place.getPhotoCount() > 0) || (place.getVideoCount() != null && place.getVideoCount() > 0)) {
            StringBuilder media = new StringBuilder("\u5f71\u50cf\u8bb0\u5f55\uff1a");
            boolean appended = false;
            if (place.getPhotoCount() != null && place.getPhotoCount() > 0) {
                media.append(place.getPhotoCount()).append(" \u5f20\u7167\u7247");
                appended = true;
            }
            if (place.getVideoCount() != null && place.getVideoCount() > 0) {
                if (appended) {
                    media.append("\u3001");
                }
                media.append(place.getVideoCount()).append(" \u4e2a\u89c6\u9891");
            }
            parts.add(media.toString());
        }
        return parts.isEmpty() ? "\u8fd9\u91cc\u7559\u4e0b\u4e86\u4e00\u6bb5\u503c\u5f97\u56de\u770b\u7684\u505c\u7559\u3002" : String.join("\uff0c", parts) + "\u3002";
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
        return !placeName.trim().matches("(?i)^(\\u5730\\u70b9|\\u505c\\u7559\\u70b9|place)\\s*\\d+$");
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
                        .replaceAll("^#{1,6}\\s*", "")
                        .replaceAll("^[-*\\u2022]\\s*", "")
                        .replaceAll("^\\d+[.)、]\\s*", "")
                        .replaceAll("(?i)(trip summary|highlights|overall feeling|summary)[:：]?", "")
                        .replaceAll("(\\u65c5\\u7a0b\\u603b\\u7ed3|\\u884c\\u7a0b\\u603b\\u7ed3|\\u603b\\u7ed3|\\u4eae\\u70b9|\\u8def\\u7ebf\\u6982\\u89c8|\\u6700\\u4f73\\u7247\\u6bb5)[:：]?", "")
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
        if (!normalized.endsWith("\u3002") && !normalized.endsWith("\uff01") && !normalized.endsWith("\uff1f")
                && !normalized.endsWith("!") && !normalized.endsWith("?")) {
            normalized = normalized + "\u3002";
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
            if (ch == '\u3002' || ch == '\uff01' || ch == '\uff1f' || ch == ',' || ch == '\uff0c') {
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

