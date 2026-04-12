package com.travel.travel_system.vo;

import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaBindingVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long mediaId;
    private Long tripId;
    private String bindingStatus;
    private Float bindingScore;

    private Long anchorId;
    private Long matchedTs;
    private Long mediaTs;
    private Long segmentId;
    private Boolean routeEligible;
    private String projectionStatus;
    private String matchMethod;
    private Float confidence;

    private String captureTimeSource;
    private String captureCoordSource;
    private String captureCoordType;
}
