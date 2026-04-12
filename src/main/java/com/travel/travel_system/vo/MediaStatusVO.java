package com.travel.travel_system.vo;

import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long mediaId;
    private Long tripId;
    private String processingStatus;
    private Integer durationSec;
    private String resolution;
}
