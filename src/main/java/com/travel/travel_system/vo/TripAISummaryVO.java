package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.List;

import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripAISummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;
    private String overview;
    private List<String> highlights;
    private String routeSummary;
    private String bestMoment;
    private String generatedAt;
    private String version;
}