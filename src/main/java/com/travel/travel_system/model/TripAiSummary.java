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
@Table(name = "trip_ai_summary")
public class TripAiSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "overview")
    private String overview;

    @Column(name = "highlights")
    private String highlights;

    @Column(name = "best_moment")
    private String bestMoment;

    @Column(name = "route_summary")
    private String routeSummary;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "version")
    private String version;

    @Column(name = "generated_at")
    private Date generatedAt;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
