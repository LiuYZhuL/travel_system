package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.CoordType;
import com.travel.travel_system.model.enums.TrackPointSource;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "track_point")
public class TrackPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "ts")
    private Long ts;

    @Column(name = "lat_enc")
    private byte[] latEnc;

    @Column(name = "lng_enc")
    private byte[] lngEnc;

    @Column(name = "accuracy_m")
    private Float accuracyM;

    @Column(name = "speed_mps")
    private Float speedMps;

    @Column(name = "heading_deg")
    private Float headingDeg;

    @Column(name = "source")
    @Enumerated(EnumType.STRING)
    private TrackPointSource source;

    @Column(name = "raw_coord_type")
    @Enumerated(EnumType.STRING)
    private CoordType rawCoordType;

    /**
     * 所属轨迹分段 ID。
     * ACTIVE 状态下采集的点应归入当前打开的 segment；
     * PAUSED 期间的点通常为 null。
     */
    @Column(name = "segment_id")
    private Long segmentId;

    /**
     * 是否参与轨迹绘制 / 路径匹配。
     * ACTIVE 段内的点通常为 true；
     * PAUSED 期间落库但不应绘制的点为 false。
     */
    @Column(name = "render_eligible")
    private Boolean renderEligible = true;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
}