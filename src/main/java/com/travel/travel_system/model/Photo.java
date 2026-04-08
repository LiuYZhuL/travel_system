package com.travel.travel_system.model;

import com.travel.travel_system.model.enums.PrivacyMode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Data
@Entity
@Table(name = "photo")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "file_hash")
    private String fileHash;

    /**
     * EXIF 拍摄时间
     */
    @Column(name = "shot_time_exif")
    private Date shotTimeExif;

    /**
     * EXIF 坐标
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

    @Column(name = "privacy_mode")
    @Enumerated(EnumType.STRING)
    private PrivacyMode privacyMode;

    @Column(name = "is_cover")
    private Boolean isCover = false;

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
     * 媒体与 trip 的归属状态：
     * PENDING / IN_TRIP / OUT_OF_TRIP / MANUAL_CONFIRMED
     */
    @Column(name = "binding_status", length = 32)
    private String bindingStatus = "PENDING";

    /**
     * 自动判定归属评分
     */
    @Column(name = "binding_score")
    private Float bindingScore;

    /**
     * 时间来源：EXIF / USER_INPUT / UPLOAD_TIME
     */
    @Column(name = "capture_time_source", length = 32)
    private String captureTimeSource;
    @Column(name = "capture_coord_type", length = 16)
    private String captureCoordType;

    /**
     * 坐标来源：EXIF / MANUAL / NONE
     */
    @Column(name = "capture_coord_source", length = 32)
    private String captureCoordSource;
}