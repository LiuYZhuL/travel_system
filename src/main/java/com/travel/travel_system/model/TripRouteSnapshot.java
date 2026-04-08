package com.travel.travel_system.model;

import jakarta.persistence.*;

import java.util.Date;

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
    @Column(name = "overview_polyline_json")
    private String overviewPolylineJson;

    @Column(name = "oss_object_key", length = 255)
    private String ossObjectKey;

    @Column(name = "oss_etag", length = 255)
    private String ossEtag;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "generated_at", nullable = false)
    private Date generatedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "created_at", nullable = false)
    private Date createdAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Date updatedAt;

    public Long getTripId() { return tripId; }
    public void setTripId(Long tripId) { this.tripId = tripId; }
    public String getRouteStatus() { return routeStatus; }
    public void setRouteStatus(String routeStatus) { this.routeStatus = routeStatus; }
    public String getAlgoVersion() { return algoVersion; }
    public void setAlgoVersion(String algoVersion) { this.algoVersion = algoVersion; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Integer getPointCount() { return pointCount; }
    public void setPointCount(Integer pointCount) { this.pointCount = pointCount; }
    public Long getStartTs() { return startTs; }
    public void setStartTs(Long startTs) { this.startTs = startTs; }
    public Long getEndTs() { return endTs; }
    public void setEndTs(Long endTs) { this.endTs = endTs; }
    public String getOverviewPolylineJson() { return overviewPolylineJson; }
    public void setOverviewPolylineJson(String overviewPolylineJson) { this.overviewPolylineJson = overviewPolylineJson; }
    public String getOssObjectKey() { return ossObjectKey; }
    public void setOssObjectKey(String ossObjectKey) { this.ossObjectKey = ossObjectKey; }
    public String getOssEtag() { return ossEtag; }
    public void setOssEtag(String ossEtag) { this.ossEtag = ossEtag; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Date getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Date generatedAt) { this.generatedAt = generatedAt; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
