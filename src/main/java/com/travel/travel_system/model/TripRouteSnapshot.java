package com.travel.travel_system.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "trip_route_snapshot")
public class TripRouteSnapshot {

    @Id
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "route_status", nullable = false, length = 20)
    private String routeStatus;

    @Column(name = "algo_version", nullable = false, length = 50)
    private String algoVersion;

    @Column(name = "fingerprint", nullable = false, length = 255)
    private String fingerprint;

    @Column(name = "point_count", nullable = false)
    private Integer pointCount;

    @Column(name = "start_ts")
    private Long startTs;

    @Column(name = "end_ts")
    private Long endTs;

    @Lob
    @Column(name = "overview_polyline_json", columnDefinition = "LONGTEXT")
    private String overviewPolylineJson;

    @Column(name = "oss_object_key", length = 255)
    private String ossObjectKey;

    @Column(name = "oss_etag", length = 255)
    private String ossEtag;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "generated_at", nullable = false)
    private Date generatedAt;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Date createdAt;


    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Date updatedAt;
}
