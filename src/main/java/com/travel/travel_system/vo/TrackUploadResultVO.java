package com.travel.travel_system.vo;

import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackUploadResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private Integer uploadedCount;
}
