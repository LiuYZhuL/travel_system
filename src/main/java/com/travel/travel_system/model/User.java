package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "open_id", unique = true, nullable = false)
    private String openId;
    
    @Column(name = "union_id")
    private String unionId;
    
    @Column(name = "nickname")
    private String nickname;
    
    @Column(name = "avatar_url")
    private String avatarUrl;
    
    @Column(name = "default_privacy_mode")
    @Enumerated(EnumType.STRING)
    private PrivacyMode defaultPrivacyMode;
    
    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
    
    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
