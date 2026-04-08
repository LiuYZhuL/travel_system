package com.travel.travel_system.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
@Data
@AllArgsConstructor
public class RoadLoadRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private String resourcePath;
    private List<GeoCoord> polylineWgs84 = new ArrayList<>();
    private double coreBufferMeters = 150.0;
    private double haloBufferMeters = 350.0;
    private boolean loadRestrictions = true;
    private boolean includeConstruction = true;
    private boolean includeDestinationAccess = true;
    private boolean includePrivateAccess = false;
    private Set<String> allowedHighways = new LinkedHashSet<>();

    public RoadLoadRequest() {
        allowedHighways.add("motorway");
        allowedHighways.add("motorway_link");
        allowedHighways.add("trunk");
        allowedHighways.add("trunk_link");
        allowedHighways.add("primary");
        allowedHighways.add("primary_link");
        allowedHighways.add("secondary");
        allowedHighways.add("secondary_link");
        allowedHighways.add("tertiary");
        allowedHighways.add("tertiary_link");
        allowedHighways.add("unclassified");
        allowedHighways.add("residential");
        allowedHighways.add("service");
        allowedHighways.add("living_street");
        allowedHighways.add("track");
    }
}
