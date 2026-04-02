package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.VideoProcessingStatus;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "video")
public class Video {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "trip_id")
    private Long tripId;
    
    @Column(name = "object_key")
    private String objectKey;
    
    @Column(name = "file_hash")
    private String fileHash;
    
    @Column(name = "thumbnail_object_key")
    private String thumbnailObjectKey;
    
    @Column(name = "duration_sec")
    private Integer durationSec;
    
    @Column(name = "file_size")
    private Long fileSize;
    
    @Column(name = "resolution")
    private String resolution;
    
    @Column(name = "shot_time_exif")
    private Date shotTimeExif;
    
    @Column(name = "lat_enc")
    private byte[] latEnc;
    
    @Column(name = "lng_enc")
    private byte[] lngEnc;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
    
    @Column(name = "user_caption", length = 500)
    private String userCaption;
    
    @Column(name = "privacy_mode")
    @Enumerated(EnumType.STRING)
    private PrivacyMode privacyMode;
    
    @Column(name = "processing_status")
    @Enumerated(EnumType.STRING)
    private VideoProcessingStatus processingStatus;
}
