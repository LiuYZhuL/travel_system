package com.travel.travel_system.model.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TrajectoryPoint implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private long id;
    private double lat;
    private double lng;
    private long timestamp;
    private double speed;
    private double heading;
    private double accuracy;
    
    private boolean isStoppingPoint;
    private Long stopClusterId;
    private Double stopDurationSec;
    
    public TrajectoryPoint(long id, double lat, double lng, long timestamp) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        this.timestamp = timestamp;
    }
}
