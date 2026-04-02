package com.travel.travel_system.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "trip_note")
public class TripNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "anchor_ts")
    private Long anchorTs;

    @Column(name = "lat_enc")
    private byte[] latEnc;

    @Column(name = "lng_enc")
    private byte[] lngEnc;

    @Column(name = "title")
    private String title;

    @Column(name = "content")
    private String content;

    @Column(name = "privacy_mode")
    private String privacyMode;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;



}
