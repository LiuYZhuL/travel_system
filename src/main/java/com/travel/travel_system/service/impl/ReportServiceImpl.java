package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.repository.PlaceSummaryRepository;
import com.travel.travel_system.repository.TripNoteRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.service.ReportService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.utils.DateTimeUtils;
import com.travel.travel_system.vo.PlaceSummaryVO;
import com.travel.travel_system.vo.StoryBlockVO;
import com.travel.travel_system.vo.TripAISummaryVO;
import com.travel.travel_system.vo.TripDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReportServiceImpl implements ReportService {

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private PlaceSummaryRepository placeSummaryRepository;

    @Autowired
    private TripNoteRepository tripNoteRepository;

    @Autowired
    private TripService tripService;

    @Override
    public Map<String, Object> generateTripReport(Long userId, Long tripId) {
        TripDetailVO detail = tripService.getTripDetail(userId, tripId);
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在"));
        Map<String, Object> liveStatistics = tripService.getTripStatistics(tripId);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportType", "TRIP_REPORT");
        report.put("generatedAt", DateTimeUtils.formatDateTime(new Date()));

        TripDetailVO.TripSummaryVO summary = detail.getTrip();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("tripId", tripId);
        overview.put("title", summary != null ? summary.getTitle() : trip.getTitle());
        overview.put("startTime", summary != null ? summary.getStartTime() : DateTimeUtils.formatDateTime(trip.getStartTime()));
        overview.put("endTime", summary != null ? summary.getEndTime() : DateTimeUtils.formatDateTime(trip.getEndTime()));
        overview.put("distanceText", summary != null ? summary.getDistanceText() : formatDistance(trip.getDistanceM()));
        overview.put("durationText", summary != null ? summary.getDurationText() : DateTimeUtils.formatDuration(trip.getDurationSec()));
        overview.put("status", summary != null && summary.getStatus() != null ? summary.getStatus().name() : (trip.getStatus() != null ? trip.getStatus().name() : null));
        report.put("overview", overview);

        List<PlaceSummaryVO> places = detail.getPlaces() != null ? detail.getPlaces() : Collections.emptyList();
        List<Map<String, Object>> placeList = places.stream()
                .map(place -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", place.getId());
                    item.put("name", place.getPoiName());
                    item.put("city", place.getCity());
                    item.put("district", place.getDistrict());
                    item.put("durationText", place.getDurationText());
                    item.put("photoCount", place.getPhotoCount() != null ? place.getPhotoCount() : 0);
                    item.put("videoCount", place.getVideoCount() != null ? place.getVideoCount() : 0);
                    return item;
                })
                .collect(Collectors.toList());
        report.put("places", placeList);

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("placeCount", places.size());
        statistics.put("photoCount", liveStatistics.getOrDefault("photoCount", 0));
        statistics.put("videoCount", liveStatistics.getOrDefault("videoCount", 0));
        statistics.put("noteCount", tripNoteRepository.countByTripId(tripId));
        statistics.put("totalDistance", liveStatistics.getOrDefault("distanceM", 0));
        statistics.put("totalDuration", liveStatistics.getOrDefault("durationSec", 0));
        statistics.put("trackPointCount", liveStatistics.getOrDefault("trackPointCount", 0));
        report.put("statistics", statistics);

        List<StoryBlockVO> blocks = detail.getStoryBlocks() != null ? detail.getStoryBlocks() : Collections.emptyList();
        report.put("storyBlocks", blocks.stream().map(this::toStoryBlockMap).collect(Collectors.toList()));

        TripAISummaryVO aiSummary = detail.getAiSummary();
        if (aiSummary != null) {
            Map<String, Object> aiSummaryMap = new LinkedHashMap<>();
            aiSummaryMap.put("overview", aiSummary.getOverview());
            aiSummaryMap.put("highlights", aiSummary.getHighlights() != null ? aiSummary.getHighlights() : Collections.emptyList());
            aiSummaryMap.put("routeSummary", aiSummary.getRouteSummary());
            aiSummaryMap.put("bestMoment", aiSummary.getBestMoment());
            report.put("aiSummary", aiSummaryMap);
        }

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
        report.put("statistics", statistics);

        Set<String> cities = new HashSet<>();
        for (Trip trip : trips) {
            List<PlaceSummary> places = placeSummaryRepository.findByTripId(trip.getId());
            for (PlaceSummary place : places) {
                if (place.getCity() != null && !place.getCity().isBlank()) {
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

    private Map<String, Object> toStoryBlockMap(StoryBlockVO block) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", block.getId());
        map.put("type", block.getType() != null ? block.getType().name() : null);
        map.put("title", block.getTitle() != null ? block.getTitle() : block.getLocationName());
        map.put("textContent", block.getText());
        map.put("sortTime", block.getSortTime());
        map.put("displayTimeText", block.getDisplayTimeText());
        map.put("coverUrl", block.getCoverMedia() != null
                ? (block.getCoverMedia().getThumbnailUrl() != null ? block.getCoverMedia().getThumbnailUrl() : block.getCoverMedia().getUrl())
                : null);
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

        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] generateImageBytes(Map<String, Object> report) {
        return "REPORT_IMAGE_PLACEHOLDER".getBytes(StandardCharsets.UTF_8);
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
