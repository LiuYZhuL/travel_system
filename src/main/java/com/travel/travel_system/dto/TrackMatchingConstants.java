package com.travel.travel_system.dto;

import com.travel.travel_system.model.enums.CoordType;

public interface TrackMatchingConstants {
        /**
         * 论文中邻域半径实验结论最优约为 40m，因此候选搜索优先从 40m 开始逐步扩展。
         */
        public static final double[] CANDIDATE_SEARCH_RADII_METERS = {40.0, 80.0, 120.0};
        public static final double[] CANDIDATE_FALLBACK_RADII_METERS = {180.0, 260.0};
        public static final double COMPLEXITY_RADIUS_METERS = 40.0;
        public static final double EXTENDED_RADIUS_MAX_HEADING_DIFF = 35.0;
        public static final double EXTENDED_RADIUS_OBSERVATION_PENALTY = 0.72;
        public static final double ROAD_SNAP_FALLBACK_METERS = 80.0;

        /** 观测 / 转移参数 */
        public static final double SIGMA_D_METERS = 20.0;
        public static final double SIGMA_THETA_DEGREES = 45.0;
        public static final double ROUTE_BETA_METERS = 100.0;
        public static final double SECOND_ORDER_BETA_METERS = 120.0;
        public static final double ANGLE_LAMBDA = 2.0;
        public static final double PARALLEL_ROAD_ANGLE_DEGREES = 18.0;
        public static final double PARALLEL_ROAD_SWITCH_PENALTY = 0.55;
        public static final double PARALLEL_ROAD_SEPARATION_METERS = 10.0;
        public static final double PARALLEL_ROAD_OBSERVATION_METERS = 15.0;
        public static final double CURVE_CONTINUITY_ANGLE_DEGREES = 45.0;
        public static final double INTERSECTION_SHARP_TURN_DEGREES = 55.0;
        public static final double INTERSECTION_CROSSING_PENALTY = 0.35;
        public static final double INTERSECTION_STRAIGHT_BONUS = 1.25;
        public static final double CURVE_CONTINUITY_BONUS = 1.15;
        public static final double MIN_PROB = 1e-12;
        public static final double NEG_INF = -1e30;

        /** 候选与分段 */
        public static final int SIMPLE_MAX_CANDIDATES = 4;
        public static final int COMPLEX_MAX_CANDIDATES = 6;
        public static final int COMPLEX_CONTEXT_POINTS = 2;
        public static final int MAX_SECOND_ORDER_SEGMENT_POINTS = 36;
        public static final double DIRECTION_COMPLEX_THRESHOLD = 0.68;
        public static final double OVERALL_COMPLEX_THRESHOLD = 0.72;
        public static final int COMPLEX_NODE_DEGREE_THRESHOLD = 4;

        /** 驾车轨迹预处理阈值 */
        public static final double MAX_DRIVING_SPEED_MPS = 35.0;      // 论文预处理图中上限约 35m/s
        public static final double MIN_MOVING_SPEED_MPS = 1.0;        // 论文预处理图中低速阈值约 1m/s
        public static final double MAX_STEP_DISTANCE_METERS = 250.0;  // 论文预处理图中相邻点距离阈值约 0.25km
        public static final double MAX_ACCURACY_METERS = 120.0;
        public static final long MIN_TIME_DELTA_MS = 1000L;

        /** 多模态分类 / 步行处理 */
        public static final double GENERAL_MAX_ACCURACY_METERS = 200.0;
        public static final double WALKING_MAX_ACCURACY_METERS = 180.0;
        public static final double WALKING_MAX_REASONABLE_SPEED_MPS = 4.2;
        public static final double WALKING_SPIKE_SPEED_MPS = 7.5;
        public static final double WALKING_NEAR_ROAD_METERS = 18.0;
        public static final double WALKING_OFF_ROAD_METERS = 30.0;
        public static final int MOTION_CLASSIFY_WINDOW_RADIUS = 2;
        public static final int MIN_MODE_SEGMENT_POINTS = 4;
        public static final long MIN_MODE_SEGMENT_DURATION_MS = 45_000L;
        public static final double DRIVE_CLASSIFY_MEDIAN_SPEED_MPS = 3.8;
        public static final double DRIVE_CLASSIFY_STRONG_SPEED_MPS = 6.5;
        public static final double WALK_CLASSIFY_STRONG_SPEED_MPS = 2.2;
        public static final double EXTREME_JUMP_SPEED_MPS = 55.0;
        public static final int WALKING_SMOOTH_RADIUS = 2;
        public static final double WALKING_STAY_RADIUS_METERS = 10.0;
        public static final long WALKING_STAY_MIN_DURATION_MS = 15_000L;
        public static final double WALKING_STAY_MAX_SPEED_MPS = 1.2;
        public static final double WALKING_SIMPLIFY_MIN_MOVE_METERS = 4.0;
        public static final long WALKING_SIMPLIFY_MAX_IDLE_GAP_MS = 30_000L;

        public static final String MODE_DRIVING = "DRIVING";
        public static final String MODE_WALKING = "WALKING";
        public static final String MODE_UNKNOWN = "UNKNOWN";

        public static final String MATCHED_DRIVING_COLOR = "#FF9500";
        public static final String MATCHED_WALKING_COLOR = "#34C759";
        public static final String RECONSTRUCTED_DRIVING_COLOR = "#007AFF";
        public static final String RECONSTRUCTED_WALKING_COLOR = "#22C55E";

        public static final CoordType INTERNAL_COORD_TYPE = CoordType.WGS84;
        public static final CoordType DISPLAY_COORD_TYPE = CoordType.GCJ02;

        /** 停留压缩阈值，避免停车抖动干扰匹配 */
        public static final double STAY_CLUSTER_RADIUS_METERS = 12.0;
        public static final long STAY_CLUSTER_MIN_DURATION_MS = 20_000L;
        public static final double STAY_CLUSTER_MAX_SPEED_MPS = 1.5;

        /** 路径重构 */
        public static final int LINEAR_INTERPOLATION_STEPS = 8;
        public static final double MAX_RECONSTRUCTION_DETOUR_RATIO = 3.2;
        public static final double MAX_RECONSTRUCTION_EXTRA_METERS = 220.0;
        public static final double LOCAL_DISCONNECT_HARD_BLOCK_METERS = 120.0;
        public static final double LAYER_STRUCTURE_SWITCH_PENALTY = 0.42;
        public static final double LAYER_STRUCTURE_KEEP_BONUS = 1.06;
        public static final double RAMP_TRANSITION_BONUS = 1.05;
        public static final double SAME_NAMED_CORRIDOR_BONUS = 1.18;
        public static final double UNNAMED_SURFACE_EXIT_PENALTY = 0.68;
        public static final double SAME_CORRIDOR_FALLBACK_MAX_ANGLE = 25.0;
        public static final double SAME_CORRIDOR_FALLBACK_MAX_DIRECT_METERS = 120.0;
        public static final double DIRECT_NODE_HANDOFF_MAX_METERS = 35.0;
        public static final double SAME_WAY_NODE_HANDOFF_MAX_METERS = 90.0;
        public static final int MAX_RECONSTRUCTION_NODE_COUNT = 12;

        /** 运行期缓存 */
        public static final int NEARBY_EDGE_CACHE_BUCKET = 20_000;
        public static final double OFFSET_CACHE_BUCKET_METERS = 5.0;
        public static final int MAX_NEARBY_EDGE_CACHE_SIZE = 4096;
        public static final int MAX_ROUTE_DISTANCE_CACHE_SIZE = 20_000;

    
}
