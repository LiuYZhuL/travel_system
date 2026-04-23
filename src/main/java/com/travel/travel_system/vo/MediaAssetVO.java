package com.travel.travel_system.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private MediaTypeVO type;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tripId;
    private String url;
    private String thumbnailUrl;
    private String shotTime;
    private String createdAt;
    private Integer durationSec;
    private String resolution;
    private String caption;
    private PrivacyModeVO privacyMode;
    private Boolean shareMasked;
    private Boolean isCover;
    private GeoPointVO point;
    private String locationName;
}
