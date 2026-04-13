package com.travel.travel_system.controller;

import com.travel.travel_system.service.ReverseGeocodingService;
import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.GeoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/geocoding")
public class GeocodingController extends BaseController {

    @Autowired
    private ReverseGeocodingService reverseGeocodingService;

    @GetMapping("/reverse")
    public ApiResponse<?> reverseGeocode(@RequestParam Double lat,
                                         @RequestParam Double lng,
                                         @RequestParam(required = false) String coordType) {
        if (lat == null || lng == null) {
            return error("VALID_001", "lat 和 lng 不能为空");
        }

        try {
            String normalizedCoordType = normalizeCoordType(coordType);
            double queryLat = lat;
            double queryLng = lng;
            if ("WGS84".equals(normalizedCoordType)) {
                double[] gcjPoint = GeoUtils.wgs84ToGcj02(lat, lng);
                queryLat = gcjPoint[0];
                queryLng = gcjPoint[1];
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("lat", lat);
            data.put("lng", lng);
            data.put("coordType", normalizedCoordType);

            reverseGeocodingService.reverseGeocode(queryLat, queryLng).ifPresent(result -> {
                data.put("name", firstNonBlank(
                        result.getDisplayLocation(),
                        result.poiName(),
                        result.district(),
                        result.getDisplayCity()
                ));
                data.put("address", result.formattedAddress());
                data.put("poiName", result.poiName());
                data.put("poiType", result.poiType());
                data.put("province", result.province());
                data.put("city", result.city());
                data.put("district", result.district());
                data.put("street", result.street());
                data.put("streetNumber", result.streetNumber());
            });

            return success(data);
        } catch (Exception e) {
            return error("SYSTEM_500", "逆地理编码失败：" + e.getMessage());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeCoordType(String coordType) {
        if (coordType == null || coordType.trim().isEmpty()) {
            return "GCJ02";
        }
        return coordType.trim().toUpperCase();
    }
}
