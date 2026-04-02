package com.travel.travel_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class NodeAccessOption {
    private long nodeId;
    private double costMeters;
}
