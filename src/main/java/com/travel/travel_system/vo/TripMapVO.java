package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripMapVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private GeoPointVO center;
    private Integer zoom;
    private BBoxVO bbox;
    private TrackPolylineVO rawPolyline;
    private TrackPolylineVO matchedPolyline;
    private TrackPolylineVO reconstructedPolyline;
    private List<MapMarkerVO> markers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BBoxVO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Double minLat;
        private Double minLng;
        private Double maxLat;
        private Double maxLng;
    }
}