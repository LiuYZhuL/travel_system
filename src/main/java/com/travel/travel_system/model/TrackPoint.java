package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.TrackPointSource;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "track_point")
public class TrackPoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "trip_id")
    private Long tripId;
    
    @Column(name = "ts")
    private Long ts;
    
    @Column(name = "lat_enc")
    private byte[] latEnc;
    
    @Column(name = "lng_enc")
    private byte[] lngEnc;
    
    @Column(name = "accuracy_m")
    private Float accuracyM;
    
    @Column(name = "speed_mps")
    private Float speedMps;
    
    @Column(name = "heading_deg")
    private Float headingDeg;
    
    @Column(name = "source")
    @Enumerated(EnumType.STRING)
    private TrackPointSource source;
    
    @Column(name = "raw_coord_type")
    @Enumerated(EnumType.STRING)
    private CoordType rawCoordType;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
}
