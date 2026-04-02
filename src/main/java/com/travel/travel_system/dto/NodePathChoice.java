package com.travel.travel_system.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class NodePathChoice {
    private NodeAccessOption startExit;
    private NodeAccessOption endEntry;
    private List<Long> nodePath;
    private double totalCostMeters;
}
