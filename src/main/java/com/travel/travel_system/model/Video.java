package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.model.enums.VideoProcessingStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "video")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "note_id")
    private Long noteId;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "thumbnail_object_key")
    private String thumbnailObjectKey;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "resolution")
    private String resolution;

    /**
     * EXIF / 元数据拍摄时间
     */
    @Column(name = "shot_time_exif")
    private Date shotTimeExif;

    /**
     * EXIF / 元数据坐标
     */
    @Column(name = "lat_enc")
    private byte[] latEnc;

    @Column(name = "lng_enc")
    private byte[] lngEnc;

    @Column(name = "created_at")
    @CreationTimestamp
    private Date createdAt;

    @Column(name = "user_caption", length = 500)
    private String userCaption;

    @Column(name = "location_name", length = 255)
    private String locationName;

    @Column(name = "privacy_mode")
    @Enumerated(EnumType.STRING)
    private PrivacyMode privacyMode;

    @Column(name = "processing_status")
    @Enumerated(EnumType.STRING)
    private VideoProcessingStatus processingStatus;

    /**
     * 用户手动修正拍摄时间戳（毫秒）
     */
    @Column(name = "capture_ts_override")
    private Long captureTsOverride;

    /**
     * 用户手动修正位置
     */
    @Column(name = "capture_lat_override")
    private byte[] captureLatOverride;

    @Column(name = "capture_lng_override")
    private byte[] captureLngOverride;

    /**
     * PENDING / IN_TRIP / OUT_OF_TRIP / MANUAL_CONFIRMED
     */
    @Column(name = "binding_status", length = 32)
    private String bindingStatus = "PENDING";

    @Column(name = "binding_score")
    private Float bindingScore;

    /**
     * EXIF / USER_INPUT / UPLOAD_TIME
     */
    @Column(name = "capture_time_source", length = 32)
    private String captureTimeSource;
    @Column(name = "capture_coord_type", length = 16)
    private String captureCoordType;

    /**
     * EXIF / MANUAL / NONE
     */
    @Column(name = "capture_coord_source", length = 32)
    private String captureCoordSource;
}
