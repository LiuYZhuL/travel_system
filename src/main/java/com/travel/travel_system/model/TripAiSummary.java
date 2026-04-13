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

    @Column(name = "overview", columnDefinition = "LONGTEXT")
    private String overview;

    @Column(name = "highlights", columnDefinition = "LONGTEXT")
    private String highlights;

    @Column(name = "best_moment", columnDefinition = "LONGTEXT")
    private String bestMoment;

    @Column(name = "route_summary", columnDefinition = "LONGTEXT")
    private String routeSummary;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "version")
    private String version;

    @Column(name = "is_latest")
    private Boolean isLatest = true;

    @Column(name = "regenerate_reason", length = 500)
    private String regenerateReason;

    @Column(name = "generated_at")
    private Date generatedAt;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}
