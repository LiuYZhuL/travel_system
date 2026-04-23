package com.travel.travel_system.controller;

import com.travel.travel_system.model.PlaceSummary;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.service.PlaceSummaryService;
import com.travel.travel_system.service.TripService;
import com.travel.travel_system.service.impl.TripAggregationRefreshService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.GeoUtils;
import com.travel.travel_system.vo.PlaceSummaryVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/trips")
public class PlaceController extends BaseController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FALLBACK_POI_PREFIX = "地点 ";

    @Autowired
    private PlaceSummaryService placeSummaryService;

    @Autowired
    private TripService tripService;
    @Autowired
    private TripAggregationRefreshService tripAggregationRefreshService;

    @GetMapping("/{tripId}/places/{placeId}")
    public ApiResponse<?> getPlaceDetail(@PathVariable Long tripId,
                                         @PathVariable Long placeId,
                                         HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            PlaceSummary place = requirePlaceInTrip(tripId, placeId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("place", findPlaceVO(userId, tripId, place.getId()));
            data.put("members", placeSummaryService.getPlaceSummaryMembers(placeId));
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "获取地点详情失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/places")
    public ApiResponse<?> createPlace(@PathVariable Long tripId,
                                      @RequestBody Map<String, Object> request,
                                      HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            Trip trip = tripService.getUserTripOrThrow(userId, tripId);

            Double lat = asDouble(firstNonNull(request.get("lat"), request.get("centerLat")));
            Double lng = asDouble(firstNonNull(request.get("lng"), request.get("lon"), request.get("centerLng")));
            if (lat == null || lng == null) {
                return error("VALID_002", "地点坐标不能为空");
            }

            double[] centerWgs84 = normalizeToInternalWgs84(lat, lng, asString(request.get("coordType")));

            Date startTime = parseFlexibleDate(firstNonNull(request.get("startTime"), request.get("startTs")));
            Date endTime = parseFlexibleDate(firstNonNull(request.get("endTime"), request.get("endTs")));
            if (startTime == null && endTime == null) {
                startTime = trip.getStartTime() != null ? trip.getStartTime() : new Date();
                endTime = startTime;
            } else if (startTime == null) {
                startTime = endTime;
            } else if (endTime == null) {
                endTime = startTime;
            }
            if (endTime.before(startTime)) {
                return error("VALID_003", "结束时间不能早于开始时间");
            }

            String poiName = trimToNull(asString(request.get("poiName")));
            boolean shouldRefreshSemantic = poiName == null;

            PlaceSummary place = new PlaceSummary();
            place.setUserId(userId);
            place.setTripId(tripId);
            place.setCenterLatEnc(encodeDouble(centerWgs84[0]));
            place.setCenterLngEnc(encodeDouble(centerWgs84[1]));
            place.setGeohash(null);
            place.setStartTime(startTime);
            place.setEndTime(endTime);
            place.setDurationSec(Math.max(0L, (endTime.getTime() - startTime.getTime()) / 1000L));
            place.setCity(trimToNull(asString(request.get("city"))));
            place.setDistrict(trimToNull(asString(request.get("district"))));
            place.setPoiName(poiName != null ? poiName : FALLBACK_POI_PREFIX + startTime.getTime());
            place.setPhotoCount(0);
            place.setVideoCount(0);
            place.setUserNotes(trimToNull(asString(request.get("userNotes"))));
            place.setUserTags(joinTags(request.get("userTags")));
            place.setPrivacyLevel(parsePrivacyMode(asString(request.get("privacyLevel")), PrivacyMode.PUBLIC));
            place.setGeneratedAt(new Date());
            place.setUpdatedAt(new Date());

            PlaceSummary saved = placeSummaryService.createPlaceSummary(place);
            if (shouldRefreshSemantic) {
                placeSummaryService.refreshSemanticInfoForTrip(tripId);
            }
            tripAggregationRefreshService.markTripDirty(tripId, "PLACE_CREATE");

            return success(buildPlaceDetail(userId, tripId, saved.getId()));
        } catch (Exception e) {
            return error("SYSTEM_500", "创建地点失败：" + e.getMessage());
        }
    }

    @PatchMapping("/{tripId}/places/{placeId}")
    public ApiResponse<?> updatePlace(@PathVariable Long tripId,
                                      @PathVariable Long placeId,
                                      @RequestBody Map<String, Object> request,
                                      HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            tripService.getUserTripOrThrow(userId, tripId);
            PlaceSummary place = requirePlaceInTrip(tripId, placeId);

            boolean locationChanged = false;
            boolean startProvided = request.containsKey("startTime") || request.containsKey("startTs");
            boolean endProvided = request.containsKey("endTime") || request.containsKey("endTs");
            boolean poiProvided = request.containsKey("poiName");
            boolean cityProvided = request.containsKey("city");
            boolean districtProvided = request.containsKey("district");
            boolean tagsProvided = request.containsKey("userTags");

            Double lat = asDouble(firstNonNull(request.get("lat"), request.get("centerLat")));
            Double lng = asDouble(firstNonNull(request.get("lng"), request.get("lon"), request.get("centerLng")));
            if (lat != null || lng != null) {
                if (lat == null || lng == null) {
                    return error("VALID_002", "修改地点位置时必须同时提供 lat 和 lng");
                }
                double[] centerWgs84 = normalizeToInternalWgs84(lat, lng, asString(request.get("coordType")));
                place.setCenterLatEnc(encodeDouble(centerWgs84[0]));
                place.setCenterLngEnc(encodeDouble(centerWgs84[1]));
                place.setGeohash(null);
                locationChanged = true;
            }

            Date startTime = startProvided
                    ? parseFlexibleDate(firstNonNull(request.get("startTime"), request.get("startTs")))
                    : place.getStartTime();
            Date endTime = endProvided
                    ? parseFlexibleDate(firstNonNull(request.get("endTime"), request.get("endTs")))
                    : place.getEndTime();
            if (startTime != null && endTime != null && endTime.before(startTime)) {
                return error("VALID_003", "结束时间不能早于开始时间");
            }
            if (startProvided) {
                place.setStartTime(startTime);
                locationChanged = true;
            }
            if (endProvided) {
                place.setEndTime(endTime);
                locationChanged = true;
            }
            if (place.getStartTime() != null && place.getEndTime() != null) {
                place.setDurationSec(Math.max(0L, (place.getEndTime().getTime() - place.getStartTime().getTime()) / 1000L));
            }

            if (poiProvided) {
                String poiName = trimToNull(asString(request.get("poiName")));
                place.setPoiName(poiName != null ? poiName : FALLBACK_POI_PREFIX + resolvePlaceTimestamp(place));
            } else if (locationChanged) {
                place.setPoiName(FALLBACK_POI_PREFIX + resolvePlaceTimestamp(place));
            }

            if (request.containsKey("userNotes")) {
                place.setUserNotes(trimToNull(asString(request.get("userNotes"))));
            }
            if (tagsProvided) {
                place.setUserTags(joinTags(request.get("userTags")));
            } else if (locationChanged) {
                place.setUserTags(null);
            }
            if (cityProvided) {
                place.setCity(trimToNull(asString(request.get("city"))));
            } else if (locationChanged) {
                place.setCity(null);
            }
            if (districtProvided) {
                place.setDistrict(trimToNull(asString(request.get("district"))));
            } else if (locationChanged) {
                place.setDistrict(null);
            }
            if (request.containsKey("privacyLevel")) {
                place.setPrivacyLevel(parsePrivacyMode(asString(request.get("privacyLevel")), place.getPrivacyLevel()));
            }
            place.setUpdatedAt(new Date());

            PlaceSummary saved = placeSummaryService.createPlaceSummary(place);
            if (locationChanged && !poiProvided) {
                placeSummaryService.refreshSemanticInfoForTrip(tripId);
            }
            tripAggregationRefreshService.markTripDirty(tripId, "PLACE_UPDATE");

            return success(buildPlaceDetail(userId, tripId, saved.getId()));
        } catch (Exception e) {
            return error("SYSTEM_500", "修改地点失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{tripId}/places/{placeId}")
    public ApiResponse<?> deletePlace(@PathVariable Long tripId,
                                      @PathVariable Long placeId,
                                      HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            requirePlaceInTrip(tripId, placeId);
            placeSummaryService.deletePlaceSummary(placeId);
            tripAggregationRefreshService.markTripDirty(tripId, "PLACE_DELETE");
            return success(Map.of("tripId", tripId, "placeId", placeId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除地点失败：" + e.getMessage());
        }
    }

    @PostMapping("/{tripId}/places/regenerate")
    public ApiResponse<?> regeneratePlaces(@PathVariable Long tripId, HttpServletRequest request) {
        try {
            Long userId = requireUserId(request);
            tripService.getUserTripOrThrow(userId, tripId);
            List<PlaceSummary> regenerated = placeSummaryService.generatePlaceSummariesForTrip(tripId);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tripId", tripId);
            data.put("placeCount", regenerated.size());
            data.put("places", tripService.getTripPlaces(userId, tripId));
            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "重新获取地点失败：" + e.getMessage());
        }
    }

    private Map<String, Object> buildPlaceDetail(Long userId, Long tripId, Long placeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("place", findPlaceVO(userId, tripId, placeId));
        data.put("members", placeSummaryService.getPlaceSummaryMembers(placeId));
        return data;
    }

    private PlaceSummaryVO findPlaceVO(Long userId, Long tripId, Long placeId) {
        return tripService.getTripPlaces(userId, tripId).stream()
                .filter(item -> Objects.equals(item.getId(), placeId))
                .findFirst()
                .orElse(null);
    }

    private PlaceSummary requirePlaceInTrip(Long tripId, Long placeId) {
        PlaceSummary place = placeSummaryService.getPlaceSummary(placeId)
                .orElseThrow(() -> new RuntimeException("地点不存在，placeId=" + placeId));
        if (!Objects.equals(place.getTripId(), tripId)) {
            throw new RuntimeException("地点不属于当前行程，placeId=" + placeId);
        }
        return place;
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String joinTags(Object rawTags) {
        if (rawTags == null) {
            return null;
        }
        if (rawTags instanceof Collection<?> collection) {
            List<String> tags = new ArrayList<>();
            for (Object item : collection) {
                String value = trimToNull(asString(item));
                if (value != null) {
                    tags.add(value);
                }
            }
            return tags.isEmpty() ? null : String.join(",", tags);
        }
        String text = trimToNull(asString(rawTags));
        return text == null ? null : text;
    }

    private PrivacyMode parsePrivacyMode(String rawValue, PrivacyMode defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            return PrivacyMode.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
    }

    private Date parseFlexibleDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date date) {
            return date;
        }
        if (value instanceof Number number) {
            return new Date(number.longValue());
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new Date(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(text, DATETIME_FORMATTER);
            return Date.from(localDateTime.atZone(DEFAULT_ZONE).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Date.from(Instant.parse(text));
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate localDate = LocalDate.parse(text);
            return Date.from(localDate.atStartOfDay(DEFAULT_ZONE).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        throw new RuntimeException("无法识别的时间格式: " + text);
    }

    private double[] normalizeToInternalWgs84(double lat, double lng, String coordTypeText) {
        CoordType coordType = parseCoordType(coordTypeText);
        if (coordType == CoordType.GCJ02) {
            return GeoUtils.gcj02ToWgs84(lat, lng);
        }
        return new double[]{lat, lng};
    }

    private CoordType parseCoordType(String coordTypeText) {
        if (coordTypeText == null || coordTypeText.isBlank()) {
            return CoordType.GCJ02;
        }
        try {
            return CoordType.valueOf(coordTypeText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CoordType.GCJ02;
        }
    }

    private long resolvePlaceTimestamp(PlaceSummary place) {
        if (place.getStartTime() != null) {
            return place.getStartTime().getTime();
        }
        if (place.getEndTime() != null) {
            return place.getEndTime().getTime();
        }
        return System.currentTimeMillis();
    }

    private byte[] encodeDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((bits >> (i * 8)) & 0xFF);
        }
        return bytes;
    }
}
