package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.*;
import com.travel.travel_system.repository.*;
import com.travel.travel_system.service.ReportService;
import com.travel.travel_system.utils.DateTimeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {

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

    @Override
    public Map<String, Object> generateTripReport(Long userId, Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在"));
        
        if (!trip.getUserId().equals(userId)) {
            throw new RuntimeException("无权访问此行程");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        
        report.put("reportType", "TRIP_REPORT");
        report.put("generatedAt", DateTimeUtils.formatDateTime(new Date()));
        
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("tripId", tripId);
        overview.put("title", trip.getTitle());
        overview.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
        overview.put("endTime", DateTimeUtils.formatDateTime(trip.getEndTime()));
        overview.put("distanceText", formatDistance(trip.getDistanceM()));
        overview.put("durationText", DateTimeUtils.formatDuration(trip.getDurationSec()));
        overview.put("status", trip.getStatus() != null ? trip.getStatus().name() : null);
        report.put("overview", overview);

        List<PlaceSummary> places = placeSummaryRepository.findByTripId(tripId);
        List<Map<String, Object>> placeList = places.stream().map(place -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", place.getId());
            item.put("name", place.getPoiName());
            item.put("city", place.getCity());
            item.put("district", place.getDistrict());
            item.put("durationText", DateTimeUtils.formatDuration(place.getDurationSec()));
            item.put("photoCount", place.getPhotoCount());
            item.put("videoCount", place.getVideoCount());
            return item;
        }).collect(Collectors.toList());
        report.put("places", placeList);

        Long photoCount = photoRepository.countByTripId(tripId);
        Long videoCount = videoRepository.countByTripId(tripId);
        Long noteCount = tripNoteRepository.countByTripId(tripId);
        
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("placeCount", places.size());
        statistics.put("photoCount", photoCount);
        statistics.put("videoCount", videoCount);
        statistics.put("noteCount", noteCount);
        statistics.put("totalDistance", trip.getDistanceM());
        statistics.put("totalDuration", trip.getDurationSec());
        report.put("statistics", statistics);

        List<StoryBlock> blocks = storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);
        List<Map<String, Object>> storyBlocks = blocks.stream()
                .filter(block -> !Boolean.TRUE.equals(block.getIsHidden()))
                .map(this::toStoryBlockMap)
                .collect(Collectors.toList());
        report.put("storyBlocks", storyBlocks);

        tripAiSummaryRepository.findByTripId(tripId).ifPresent(aiSummary -> {
            Map<String, Object> aiSummaryMap = new LinkedHashMap<>();
            aiSummaryMap.put("overview", aiSummary.getOverview());
            aiSummaryMap.put("highlights", aiSummary.getHighlights() != null 
                    ? Arrays.asList(aiSummary.getHighlights().split("\n")) 
                    : Collections.emptyList());
            aiSummaryMap.put("routeSummary", aiSummary.getRouteSummary());
            aiSummaryMap.put("bestMoment", aiSummary.getBestMoment());
            report.put("aiSummary", aiSummaryMap);
        });

        return report;
    }

    @Override
    public Map<String, Object> generateYearlyReport(Long userId, Integer year) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "YEARLY_REPORT");
        report.put("year", year);
        report.put("generatedAt", DateTimeUtils.formatDateTime(new Date()));

        Date startDate = parseYearStart(year);
        Date endDate = parseYearEnd(year);
        
        List<Trip> trips = tripRepository.findByUserIdAndStartTimeBetween(userId, startDate, endDate);
        
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("tripCount", trips.size());
        statistics.put("totalDistance", trips.stream().mapToLong(t -> t.getDistanceM() != null ? t.getDistanceM() : 0L).sum());
        statistics.put("totalDuration", trips.stream().mapToLong(t -> t.getDurationSec() != null ? t.getDurationSec() : 0L).sum());
        statistics.put("totalPhotos", trips.stream().mapToInt(t -> t.getPhotoCount() != null ? t.getPhotoCount() : 0).sum());
        statistics.put("totalVideos", trips.stream().mapToInt(t -> t.getVideoCount() != null ? t.getVideoCount() : 0).sum());
        report.put("statistics", statistics);

        Set<String> cities = new HashSet<>();
        for (Trip trip : trips) {
            List<PlaceSummary> places = placeSummaryRepository.findByTripId(trip.getId());
            for (PlaceSummary place : places) {
                if (place.getCity() != null) {
                    cities.add(place.getCity());
                }
            }
        }
        report.put("citiesVisited", cities.size());
        report.put("cityList", new ArrayList<>(cities));

        List<Map<String, Object>> tripSummaries = trips.stream()
                .sorted(Comparator.comparing(Trip::getStartTime).reversed())
                .limit(10)
                .map(trip -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tripId", trip.getId());
                    item.put("title", trip.getTitle());
                    item.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
                    item.put("distanceText", formatDistance(trip.getDistanceM()));
                    return item;
                })
                .collect(Collectors.toList());
        report.put("recentTrips", tripSummaries);

        return report;
    }

    @Override
    public Map<String, Object> generateMonthlyReport(Long userId, Integer year, Integer month) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "MONTHLY_REPORT");
        report.put("year", year);
        report.put("month", month);
        report.put("generatedAt", DateTimeUtils.formatDateTime(new Date()));

        Date startDate = parseMonthStart(year, month);
        Date endDate = parseMonthEnd(year, month);
        
        List<Trip> trips = tripRepository.findByUserIdAndStartTimeBetween(userId, startDate, endDate);
        
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("tripCount", trips.size());
        statistics.put("totalDistance", trips.stream().mapToLong(t -> t.getDistanceM() != null ? t.getDistanceM() : 0L).sum());
        statistics.put("totalDuration", trips.stream().mapToLong(t -> t.getDurationSec() != null ? t.getDurationSec() : 0L).sum());
        report.put("statistics", statistics);

        List<Map<String, Object>> tripList = trips.stream()
                .sorted(Comparator.comparing(Trip::getStartTime))
                .map(trip -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tripId", trip.getId());
                    item.put("title", trip.getTitle());
                    item.put("startTime", DateTimeUtils.formatDateTime(trip.getStartTime()));
                    item.put("endTime", DateTimeUtils.formatDateTime(trip.getEndTime()));
                    item.put("distanceText", formatDistance(trip.getDistanceM()));
                    item.put("durationText", DateTimeUtils.formatDuration(trip.getDurationSec()));
                    return item;
                })
                .collect(Collectors.toList());
        report.put("trips", tripList);

        return report;
    }

    @Override
    public byte[] exportTripReportPdf(Long userId, Long tripId) {
        Map<String, Object> report = generateTripReport(userId, tripId);
        return generatePdfBytes(report);
    }

    @Override
    public byte[] exportTripReportImage(Long userId, Long tripId) {
        Map<String, Object> report = generateTripReport(userId, tripId);
        return generateImageBytes(report);
    }

    private Map<String, Object> toStoryBlockMap(StoryBlock block) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", block.getId());
        map.put("type", block.getBlockType() != null ? block.getBlockType().name() : null);
        map.put("title", block.getTitle());
        map.put("textContent", block.getTextContent());
        map.put("sortTime", DateTimeUtils.formatDateTime(block.getSortTime()));
        map.put("coverUrl", block.getCoverObjectKey());
        return map;
    }

    private byte[] generatePdfBytes(Map<String, Object> report) {
        StringBuilder content = new StringBuilder();
        content.append("旅行报告\n\n");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> overview = (Map<String, Object>) report.get("overview");
        if (overview != null) {
            content.append("行程概览\n");
            content.append("标题：").append(overview.get("title")).append("\n");
            content.append("开始时间：").append(overview.get("startTime")).append("\n");
            content.append("结束时间：").append(overview.get("endTime")).append("\n");
            content.append("总距离：").append(overview.get("distanceText")).append("\n");
            content.append("总时长：").append(overview.get("durationText")).append("\n\n");
        }
        
        return content.toString().getBytes();
    }

    private byte[] generateImageBytes(Map<String, Object> report) {
        return "REPORT_IMAGE_PLACEHOLDER".getBytes();
    }

    private String formatDistance(Long meters) {
        if (meters == null || meters <= 0) return "0 m";
        if (meters >= 1000) return String.format("%.1f km", meters / 1000.0);
        return meters + " m";
    }

    private Date parseYearStart(Integer year) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private Date parseYearEnd(Integer year) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, Calendar.DECEMBER, 31, 23, 59, 59);
        cal.set(Calendar.MILLISECOND, 999);
        return cal.getTime();
    }

    private Date parseMonthStart(Integer year, Integer month) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private Date parseMonthEnd(Integer year, Integer month) {
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MONTH, 1);
        cal.add(Calendar.MILLISECOND, -1);
        return cal.getTime();
    }
}
