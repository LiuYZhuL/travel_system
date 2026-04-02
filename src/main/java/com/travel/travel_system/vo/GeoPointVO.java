package com.travel.travel_system.vo;

import com.travel.travel_system.vo.enums.CoordTypeVO;
import lombok.*;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoPointVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Double lat;
    private Double lng;
    private CoordTypeVO coordType;
    private Double accuracyM;
    private Long ts;
}