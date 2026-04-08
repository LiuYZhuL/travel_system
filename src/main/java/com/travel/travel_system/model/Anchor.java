package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.MatchMethod;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "anchor")
public class Anchor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "photo_id")
    private Long photoId;

    @Column(name = "video_id")
    private Long videoId;

    /**
     * 轨迹匹配时间戳（最终锚定到轨迹上的时间）
     */
    @Column(name = "matched_ts")
    private Long matchedTs;

    /**
     * 最终投影坐标
     * 允许为空：PENDING / OUT_OF_TRIP 时可能还没有最终点
     */
    @Column(name = "lat_enc")
    private byte[] latEnc;

    @Column(name = "lng_enc")
    private byte[] lngEnc;

    @Column(name = "match_method")
    @Enumerated(EnumType.STRING)
    private MatchMethod matchMethod;

    @Column(name = "time_delta_sec")
    private Integer timeDeltaSec;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "manual_override")
    private Boolean manualOverride;

    /**
     * 媒体最终采用的排序时间戳（不一定等于 matchedTs）
     */
    @Column(name = "media_ts")
    private Long mediaTs;

    /**
     * 所属轨迹分段
     */
    @Column(name = "segment_id")
    private Long segmentId;

    /**
     * 是否允许作为辅助点进入轨迹处理
     */
    @Column(name = "route_eligible")
    private Boolean routeEligible = false;

    /**
     * PENDING / PROJECTED / OUT_OF_TRIP / MANUAL_FIXED
     */
    @Column(name = "projection_status", length = 32)
    private String projectionStatus = "PENDING";

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    private Date updatedAt;
}