package com.travel.travel_system.model;

import lombok.Data;
import jakarta.persistence.*;

@Data
@Entity
@Table(name = "trip_bbox")
public class TripBBox {
    @Id
    @Column(name = "trip_id")
    private Long tripId;
    
    @Column(name = "min_lat")
    private Float minLat;
    
    @Column(name = "min_lng")
    private Float minLng;
    
    @Column(name = "max_lat")
    private Float maxLat;
    
    @Column(name = "max_lng")
    private Float maxLng;
}
