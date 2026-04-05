package com.travel.travel_system.model;

import lombok.Data;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "user_permission")
public class UserPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "location_enabled")
    private Boolean locationEnabled;

    @Column(name = "location_granted_at")
    private Date locationGrantedAt;

    @Column(name = "album_enabled")
    private Boolean albumEnabled;

    @Column(name = "album_granted_at")
    private Date albumGrantedAt;

    @Column(name = "camera_enabled")
    private Boolean cameraEnabled;

    @Column(name = "camera_granted_at")
    private Date cameraGrantedAt;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
