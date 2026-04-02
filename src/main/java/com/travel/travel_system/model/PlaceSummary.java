package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "place_summary")
public class PlaceSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "trip_id")
    private Long tripId;
    
    @Column(name = "center_lat_enc")
    private byte[] centerLatEnc;
    
    @Column(name = "center_lng_enc")
    private byte[] centerLngEnc;
    
    @Column(name = "geohash")
    private String geohash;
    
    @Column(name = "start_time")
    private Date startTime;
    
    @Column(name = "end_time")
    private Date endTime;
    
    @Column(name = "duration_sec")
    private Long durationSec;
    
    @Column(name = "city")
    private String city;
    
    @Column(name = "district")
    private String district;
    
    @Column(name = "poi_name")
    private String poiName;

    @Column(name = "photo_cover_id")
    private Long photoCoverId;

    @Column(name = "video_cover_id")
    private Long videoCoverId;

    @Column(name = "photo_count")
    private Integer photoCount;

    @Column(name = "video_count")
    private Integer videoCount;
    
    @Column(name = "privacy_level")
    @Enumerated(EnumType.STRING)
    private PrivacyMode privacyLevel;
    
    @Column(name = "generated_at")
    private Date generatedAt;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
    
    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;

    @Column(name = "user_notes", columnDefinition = "TEXT")
    private String userNotes;
    
    @Column(name = "user_tags", length = 255)
    private String userTags;
}
