package com.travel.travel_system.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "place_summary_member")
public class PlaceSummaryMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "place_summary_id", nullable = false)
    private Long placeSummaryId;

    /**
     * TRACK_POINT / ANCHOR / PHOTO / VIDEO / NOTE
     */
    @Column(name = "member_type", length = 32, nullable = false)
    private String memberType;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /**
     * CORE / EVIDENCE / COVER
     */
    @Column(name = "member_role", length = 32)
    private String memberRole;

    @Column(name = "score")
    private Float score;

    @Column(name = "sort_index")
    private Integer sortIndex;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;
}
