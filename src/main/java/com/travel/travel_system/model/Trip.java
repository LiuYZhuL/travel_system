package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.TripStatus;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "trip")
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "title")
    private String title;
    
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private TripStatus status;
    
    @Column(name = "start_time")
    private Date startTime;
    
    @Column(name = "end_time")
    private Date endTime;
    
    @Column(name = "timezone")
    private String timezone;
    
    @Column(name = "summary_text")
    private String summaryText;
    
    @Column(name = "privacy_mode")
    @Enumerated(EnumType.STRING)
    private PrivacyMode privacyMode;
    
    @Column(name = "distance_m")
    private Long distanceM;
    
    @Column(name = "duration_sec")
    private Long durationSec;
    
    @Column(name = "photo_count")
    private Integer photoCount;

    @Column(name = "video_count")
    private Integer videoCount;
    
    @Column(name = "generated_at")
    private Date generatedAt;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
    
    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
