package com.travel.travel_system.vo;

import lombok.*;
import java.io.Serializable;
import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackPolylineVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<GeoPointVO> points;
    private Long distanceM;
    private Boolean simplified;
    private List<SegmentVO> segments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SegmentVO implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 轨迹段类型：WALKING / DRIVING / UNKNOWN
         */
        private String mode;

        /**
         * 建议前端使用的颜色
         */
        private String color;

        /**
         * 建议前端使用的线宽
         */
        private Integer width;

        /**
         * 是否虚线
         */
        private Boolean dottedLine;

        /**
         * 当前段是否经过抽稀/简化
         */
        private Boolean simplified;

        /**
         * 当前段距离
         */
        private Long distanceM;

        /**
         * 当前段轨迹点
         */
        private List<GeoPointVO> points;
    }
}
