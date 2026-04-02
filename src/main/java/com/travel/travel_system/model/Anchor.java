package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.MatchMethod;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "anchor")
public class Anchor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "trip_id")
    private Long tripId;
    
    @Column(name = "photo_id")
    private Long photoId;

    @Column(name = "video_id")
    private Long videoId;
    
    @Column(name = "matched_ts")
    private Long matchedTs;
    
    @Column(name = "lat_enc")
    private byte[] latEnc;
    
    @Column(name = "lng_enc")
    private byte[] lngEnc;
    
    @Column(name = "match_method")
    @Enumerated(EnumType.STRING)
    private MatchMethod matchMethod;
    
    @Column(name = "time_delta_sec")
    private Integer timeDeltaSec;
    
    @Column(name = "confidence")
    private Float confidence;
    
    @Column(name = "manual_override")
    private Boolean manualOverride;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
    
    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
