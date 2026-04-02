package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import com.travel.travel_system.vo.enums.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MapMarkerVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private MarkerTypeVO type;
    private GeoPointVO point;
    private String title;
    private String subTitle;
    private String iconUrl;
    private String coverUrl;
    private Long mediaId;
    private Long placeId;
    private String calloutText;
}