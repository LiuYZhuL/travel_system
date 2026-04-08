package com.travel.travel_system.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.util.Date;

@Data
@Entity
@Table(
        name = "trip_segment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trip_segment_no", columnNames = {"trip_id", "segment_no"})
        }
)
public class TripSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属行程 ID
     */
    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /**
     * 分段序号，从 1 开始
     */
    @Column(name = "segment_no", nullable = false)
    private Integer segmentNo;

    /**
     * 分段开始时间戳（毫秒）
     */
    @Column(name = "start_ts", nullable = false)
    private Long startTs;

    /**
     * 分段结束时间戳（毫秒），未闭合时可为空
     */
    @Column(name = "end_ts")
    private Long endTs;

    /**
     * 开始原因：TRIP_START / RESUME
     */
    @Column(name = "start_reason", nullable = false, length = 32)
    private String startReason;

    /**
     * 结束原因：PAUSE / FINISH
     */
    @Column(name = "end_reason", length = 32)
    private String endReason;

    /**
     * 当前分段是否闭合
     */
    @Column(name = "is_closed", nullable = false)
    private Boolean isClosed = false;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Date updatedAt;
}