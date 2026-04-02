package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserHeatmapVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private HeatmapScopeVO scope;
    private List<HeatmapPointVO> points;
}