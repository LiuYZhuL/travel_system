package com.travel.travel_system.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(of = {"id"})
public class RoadNode implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private long id;
    private double lat;
    private double lon;
    private int degree;
}
