package com.travel.travel_system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripMapVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private GeoPointVO center;
    private Integer zoom;
    private BBoxVO bbox;

    /**
     * 旧版单段字段：兼容保留
     */
    private TrackPolylineVO rawPolyline;
    private TrackPolylineVO matchedPolyline;
    private TrackPolylineVO reconstructedPolyline;

    /**
     * 旧版通用 marker：兼容保留
     */
    private List<MapMarkerVO> markers;

    /**
     * 新版多段字段
     */
    private List<TrackPolylineVO> rawSegments;
    private List<TrackPolylineVO> matchedSegments;
    private List<TrackPolylineVO> reconstructedSegments;

    /**
     * 媒体锚点 marker（照片/视频）
     */
    private List<MapMarkerVO> mediaMarkers;
    private Map<String, Object> matchingDiagnostics;
    private String routeSource;
    private String routeSyncStatus;
    private String routeGeneratedAt;

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
