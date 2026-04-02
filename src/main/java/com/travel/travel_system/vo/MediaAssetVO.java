package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import com.travel.travel_system.vo.enums.MediaTypeVO;
import com.travel.travel_system.vo.enums.PrivacyModeVO;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaAssetVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private MediaTypeVO type;
    private Long tripId;
    private String url;
    private String thumbnailUrl;
    private String shotTime;
    private String createdAt;
    private Integer durationSec;
    private String resolution;
    private String caption;
    private PrivacyModeVO privacyMode;
    private Boolean isCover;
    private GeoPointVO point;
    private String locationName;
}