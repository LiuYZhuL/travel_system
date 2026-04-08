package com.travel.travel_system.dto;

import com.travel.travel_system.vo.TrackPolylineVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TripRouteSnapshotPayload {
    private Long tripId;
    private String algoVersion;
    private String fingerprint;
    private Integer pointCount;
    private Long startTs;
    private Long endTs;
    private Long generatedAt;
    private TrackPolylineVO matchedPolyline;
    private TrackPolylineVO reconstructedPolyline;
    private List<MapMatchingResult> matchedResults;
}
