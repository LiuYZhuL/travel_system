package com.travel.travel_system.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.travel_system.config.GeocodingConfig;
import com.travel.travel_system.service.ReverseGeocodingService;
import com.travel.travel_system.service.pub.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class ReverseGeocodingServiceImpl implements ReverseGeocodingService {

    private static final Logger log = LoggerFactory.getLogger(ReverseGeocodingServiceImpl.class);

    private static final String CACHE_KEY_PREFIX = "geocoding:regeo:";
    private static final String GEOHASH_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz";

    @Autowired
    private GeocodingConfig geocodingConfig;

    @Autowired
    private RedisService redisService;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Optional<ReverseGeocodingResult> reverseGeocode(double lat, double lng) {
        String cacheKey = buildCacheKey(lat, lng);

        try {
            String cached = redisService.getString(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                return Optional.of(parseCachedResult(cached, lat, lng));
            }
        } catch (Exception e) {
            log.debug("[ReverseGeocoding] 读取缓存失败: {}", e.getMessage());
        }

        Optional<ReverseGeocodingResult> result = doReverseGeocode(lat, lng);

        result.ifPresent(r -> {
            try {
                redisService.setString(cacheKey, toJsonString(r), 
                    geocodingConfig.getCacheExpireHours() * 3600L);
            } catch (Exception e) {
                log.debug("[ReverseGeocoding] 写入缓存失败: {}", e.getMessage());
            }
        });

        return result;
    }

    @Override
    public List<ReverseGeocodingResult> batchReverseGeocode(List<double[]> points) {
        List<ReverseGeocodingResult> results = new ArrayList<>();
        for (double[] point : points) {
            if (point != null && point.length >= 2) {
                reverseGeocode(point[0], point[1]).ifPresent(results::add);
            }
        }
        return results;
    }

    private Optional<ReverseGeocodingResult> doReverseGeocode(double lat, double lng) {
        String provider = geocodingConfig.getProvider();

        for (int retry = 0; retry < geocodingConfig.getMaxRetries(); retry++) {
            try {
                if ("amap".equalsIgnoreCase(provider)) {
                    return reverseGeocodeAmap(lat, lng);
                } else if ("tencent".equalsIgnoreCase(provider)) {
                    return reverseGeocodeTencent(lat, lng);
                } else {
                    log.warn("[ReverseGeocoding] 不支持的地理编码提供商: {}", provider);
                    return Optional.empty();
                }
            } catch (RestClientException e) {
                log.warn("[ReverseGeocoding] 请求失败 (重试 {}/{}): {}", 
                    retry + 1, geocodingConfig.getMaxRetries(), e.getMessage());
                if (retry < geocodingConfig.getMaxRetries() - 1) {
                    try {
                        Thread.sleep(1000L * (retry + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        return Optional.empty();
    }

    private Optional<ReverseGeocodingResult> reverseGeocodeAmap(double lat, double lng) {
        String key = geocodingConfig.getAmap().getKey();
        if (key == null || key.isEmpty()) {
            log.debug("[ReverseGeocoding] 高德地图 API Key 未配置");
            return Optional.empty();
        }

        String url = String.format("%s?key=%s&location=%.6f,%.6f&extensions=all&output=JSON",
            geocodingConfig.getAmap().getBaseUrl(), key, lng, lat);

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.path("status").asText();

            if (!"1".equals(status)) {
                log.debug("[ReverseGeocoding] 高德API返回错误: {}", 
                    root.path("info").asText());
                return Optional.empty();
            }

            JsonNode regeocode = root.path("regeocode");
            JsonNode addressComponent = regeocode.path("addressComponent");

            String province = addressComponent.path("province").asText(null);
            String city = addressComponent.path("city").asText(null);
            String district = addressComponent.path("district").asText(null);
            String street = addressComponent.path("streetNumber").path("street").asText(null);
            String streetNumber = addressComponent.path("streetNumber").path("number").asText(null);
            String formattedAddress = regeocode.path("formatted_address").asText(null);

            String poiName = null;
            String poiType = null;
            JsonNode pois = regeocode.path("pois");
            if (pois.isArray() && pois.size() > 0) {
                JsonNode nearestPoi = pois.get(0);
                poiName = nearestPoi.path("name").asText(null);
                poiType = nearestPoi.path("type").asText(null);
            }

            return Optional.of(new ReverseGeocodingResult(
                province, city, district, street, streetNumber,
                poiName, poiType, formattedAddress, lat, lng
            ));

        } catch (Exception e) {
            log.error("[ReverseGeocoding] 解析高德API响应失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReverseGeocodingResult> reverseGeocodeTencent(double lat, double lng) {
        String key = geocodingConfig.getTencent().getKey();
        if (key == null || key.isEmpty()) {
            log.debug("[ReverseGeocoding] 腾讯地图 API Key 未配置");
            return Optional.empty();
        }

        String url = String.format("%s?key=%s&location=%.6f,%.6f&output=json",
            geocodingConfig.getTencent().getBaseUrl(), key, lat, lng);

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            int status = root.path("status").asInt();

            if (status != 0) {
                log.debug("[ReverseGeocoding] 腾讯API返回错误: {}", 
                    root.path("message").asText());
                return Optional.empty();
            }

            JsonNode result = root.path("result");
            JsonNode addressComponent = result.path("address_component");

            String province = addressComponent.path("province").asText(null);
            String city = addressComponent.path("city").asText(null);
            String district = addressComponent.path("district").asText(null);
            String street = addressComponent.path("street").asText(null);
            String streetNumber = addressComponent.path("street_number").asText(null);
            String formattedAddress = result.path("address").asText(null);

            String poiName = null;
            String poiType = null;
            JsonNode pois = result.path("pois");
            if (pois.isArray() && pois.size() > 0) {
                JsonNode nearestPoi = pois.get(0);
                poiName = nearestPoi.path("title").asText(null);
                poiType = nearestPoi.path("category").asText(null);
            }

            return Optional.of(new ReverseGeocodingResult(
                province, city, district, street, streetNumber,
                poiName, poiType, formattedAddress, lat, lng
            ));

        } catch (Exception e) {
            log.error("[ReverseGeocoding] 解析腾讯API响应失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildCacheKey(double lat, double lng) {
        String geohash = encodeGeohash(lat, lng, 8);
        return CACHE_KEY_PREFIX + geohash;
    }

    private String encodeGeohash(double lat, double lng, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lngRange = {-180.0, 180.0};
        StringBuilder hash = new StringBuilder();
        int bit = 0;
        char ch = 0;
        boolean even = true;

        while (hash.length() < precision) {
            double mid;
            if (even) {
                mid = (lngRange[0] + lngRange[1]) / 2.0;
                if (lng >= mid) {
                    ch |= 1 << (4 - bit);
                    lngRange[0] = mid;
                } else {
                    lngRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2.0;
                if (lat >= mid) {
                    ch |= 1 << (4 - bit);
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            even = !even;
            bit++;

            if (bit == 5) {
                hash.append(GEOHASH_CHARS.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }

        return hash.toString();
    }

    private ReverseGeocodingResult parseCachedResult(String cached, double lat, double lng) {
        try {
            JsonNode node = objectMapper.readTree(cached);
            return new ReverseGeocodingResult(
                node.path("province").asText(null),
                node.path("city").asText(null),
                node.path("district").asText(null),
                node.path("street").asText(null),
                node.path("streetNumber").asText(null),
                node.path("poiName").asText(null),
                node.path("poiType").asText(null),
                node.path("formattedAddress").asText(null),
                lat, lng
            );
        } catch (Exception e) {
            return new ReverseGeocodingResult(null, null, null, null, null, null, null, null, lat, lng);
        }
    }

    private String toJsonString(ReverseGeocodingResult result) {
        return String.format(
            "{\"province\":\"%s\",\"city\":\"%s\",\"district\":\"%s\",\"street\":\"%s\"," +
            "\"streetNumber\":\"%s\",\"poiName\":\"%s\",\"poiType\":\"%s\",\"formattedAddress\":\"%s\"}",
            escapeJson(result.province()),
            escapeJson(result.city()),
            escapeJson(result.district()),
            escapeJson(result.street()),
            escapeJson(result.streetNumber()),
            escapeJson(result.poiName()),
            escapeJson(result.poiType()),
            escapeJson(result.formattedAddress())
        );
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
