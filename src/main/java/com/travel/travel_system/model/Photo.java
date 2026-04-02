package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "photo")
public class Photo {
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

    @Column(name = "is_cover")
    private Boolean isCover = false;
}
