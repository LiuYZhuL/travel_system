package com.travel.travel_system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "geocoding")
public class GeocodingConfig {

    private String provider = "amap";

    private AmapConfig amap = new AmapConfig();

    private TencentConfig tencent = new TencentConfig();

    private int cacheExpireHours = 24;

    private int requestTimeoutMs = 5000;

    private int maxRetries = 3;

    @Data
    public static class AmapConfig {
        private String key;
        private String baseUrl = "https://restapi.amap.com/v3/geocode/regeo";
    }

    @Data
    public static class TencentConfig {
        private String key;
        private String baseUrl = "https://apis.map.qq.com/ws/geocoder/v1/";
    }
}
