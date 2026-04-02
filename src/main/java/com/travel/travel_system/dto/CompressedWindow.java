package com.travel.travel_system.dto;

import com.travel.travel_system.model.TrackPoint;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CompressedWindow {
    private int startIndex;
    private int endIndex;
    private TrackPoint representative;
    private List<TrackPoint> members;
}
