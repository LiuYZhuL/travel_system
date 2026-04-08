package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RoadRestriction implements Serializable {
    private static final long serialVersionUID = 1L;

    private long relationId;
    private String type;
    private long fromWayId;
    private long toWayId;
    private Long viaNodeId;
    private List<Long> viaWayIds = new ArrayList<>();
    private String exceptModes;
}
