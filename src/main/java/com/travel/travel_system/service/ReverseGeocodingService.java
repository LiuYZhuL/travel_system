package com.travel.travel_system.service;

import java.util.Optional;

public interface ReverseGeocodingService {

    /**
     * 逆地理编码：根据坐标获取地址信息
     * @param lat 纬度
     * @param lng 经度
     * @return 地址信息
     */
    Optional<ReverseGeocodingResult> reverseGeocode(double lat, double lng);

    /**
     * 批量逆地理编码
     * @param points 坐标点列表，每个元素为 [lat, lng]
     * @return 地址信息列表
     */
    java.util.List<ReverseGeocodingResult> batchReverseGeocode(java.util.List<double[]> points);

    /**
     * 逆地理编码结果
     */
    record ReverseGeocodingResult(
        String province,
        String city,
        String district,
        String street,
        String streetNumber,
        String poiName,
        String poiType,
        String formattedAddress,
        double lat,
        double lng
    ) {
        public String getDisplayCity() {
            if (city != null && !city.isEmpty()) {
                return city;
            }
            return province;
        }

        public String getDisplayLocation() {
            StringBuilder sb = new StringBuilder();
            if (poiName != null && !poiName.isEmpty()) {
                sb.append(poiName);
            } else if (street != null && !street.isEmpty()) {
                sb.append(street);
                if (streetNumber != null && !streetNumber.isEmpty()) {
                    sb.append(streetNumber);
                }
            } else if (district != null && !district.isEmpty()) {
                sb.append(district);
            } else if (city != null && !city.isEmpty()) {
                sb.append(city);
            }
            return sb.length() > 0 ? sb.toString() : formattedAddress;
        }
    }
}
