package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeatmapPointVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Double lat;
    private Double lng;
    private Integer weight;
}