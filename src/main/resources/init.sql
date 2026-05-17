/*
 Navicat Premium Dump SQL

 Source Server         : mysql
 Source Server Type    : MySQL
 Source Server Version : 80034 (8.0.34)
 Source Host           : localhost:3306
 Source Schema         : travel_system

 Target Server Type    : MySQL
 Target Server Version : 80034 (8.0.34)
 File Encoding         : 65001

 Date: 05/05/2026 16:31:58
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for anchor
-- ----------------------------
DROP TABLE IF EXISTS `anchor`;
CREATE TABLE `anchor`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '锚点 ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户 ID',
  `trip_id` bigint NOT NULL COMMENT '归属行程ID',
  `photo_id` bigint NULL DEFAULT NULL COMMENT '关联照片ID（与 video_id 至少一个不为 NULL）',
  `video_id` bigint NULL DEFAULT NULL COMMENT '关联视频 ID（新增，与 photo_id 至少一个不为 NULL）',
  `matched_ts` bigint NULL DEFAULT NULL COMMENT '匹配到的轨迹时间戳',
  `lat_enc` varbinary(255) NULL DEFAULT NULL,
  `lng_enc` varbinary(255) NULL DEFAULT NULL,
  `match_method` enum('EXIF_DIRECT','TIME_NEAREST','INTERPOLATE','MANUAL_PICK') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '匹配方法，可能值：EXIF_DIRECT, TIME_NEAREST, INTERPOLATE, MANUAL_PICK',
  `time_delta_sec` int NULL DEFAULT NULL COMMENT '时间差（秒）',
  `confidence` float NOT NULL COMMENT '匹配置信度（0~1）',
  `manual_override` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否手动校准过',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `media_ts` bigint NULL DEFAULT NULL COMMENT '媒体最终采用的排序时间戳',
  `segment_id` bigint NULL DEFAULT NULL COMMENT '所属轨迹分段ID',
  `route_eligible` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否允许进入轨迹辅助',
  `projection_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_anchor`(`user_id` ASC) USING BTREE,
  INDEX `fk_photo_anchor`(`photo_id` ASC) USING BTREE,
  INDEX `fk_video_anchor`(`video_id` ASC) USING BTREE,
  INDEX `fk_anchor_segment`(`segment_id` ASC) USING BTREE,
  INDEX `idx_anchor_trip_segment_ts`(`trip_id` ASC, `segment_id` ASC, `media_ts` ASC) USING BTREE,
  INDEX `idx_anchor_trip_route_eligible`(`trip_id` ASC, `route_eligible` ASC, `projection_status` ASC) USING BTREE,
  CONSTRAINT `fk_anchor_segment` FOREIGN KEY (`segment_id`) REFERENCES `trip_segment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_photo_anchor` FOREIGN KEY (`photo_id`) REFERENCES `photo` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_trip_anchor` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_anchor` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_video_anchor` FOREIGN KEY (`video_id`) REFERENCES `video` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_anchor_media` CHECK ((`photo_id` is not null) or (`video_id` is not null))
) ENGINE = InnoDB AUTO_INCREMENT = 18 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for photo
-- ----------------------------
DROP TABLE IF EXISTS `photo`;
CREATE TABLE `photo`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '照片ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `trip_id` bigint NOT NULL COMMENT '归属行程ID',
  `object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '对象存储路径（唯一）',
  `file_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '文件哈希值，用于去重',
  `shot_time_exif` datetime NULL DEFAULT NULL COMMENT 'Exif拍摄时间',
  `lat_enc` varbinary(255) NULL DEFAULT NULL,
  `lng_enc` varbinary(255) NULL DEFAULT NULL,
  `created_at` datetime NOT NULL COMMENT '上传时间',
  `user_caption` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户添加的照片说明',
  `privacy_mode` enum('PUBLIC','MASKED','PRIVATE') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '覆盖行程默认设置的个体隐私级别',
  `is_cover` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否作为行程封面展示',
  `capture_ts_override` bigint NULL DEFAULT NULL COMMENT '用户修正后的拍摄时间戳',
  `capture_lat_override` varbinary(255) NULL DEFAULT NULL,
  `capture_lng_override` varbinary(255) NULL DEFAULT NULL,
  `binding_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `binding_score` float NULL DEFAULT NULL COMMENT '媒体归属评分',
  `capture_time_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'EXIF/USER_INPUT/UPLOAD_TIME',
  `capture_coord_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'EXIF/MANUAL/NONE',
  `capture_coord_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '媒体坐标类型：WGS84 / GCJ02',
  `location_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `note_id` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `object_key`(`object_key` ASC) USING BTREE,
  INDEX `fk_user_photo`(`user_id` ASC) USING BTREE,
  INDEX `idx_photo_trip_binding`(`trip_id` ASC, `binding_status` ASC, `shot_time_exif` ASC) USING BTREE,
  CONSTRAINT `fk_trip_photo` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_photo` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for place_summary
-- ----------------------------
DROP TABLE IF EXISTS `place_summary`;
CREATE TABLE `place_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '地点摘要ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `trip_id` bigint NOT NULL COMMENT '归属行程ID',
  `center_lat_enc` varbinary(255) NULL DEFAULT NULL,
  `center_lng_enc` varbinary(255) NULL DEFAULT NULL,
  `geohash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `start_time` datetime NOT NULL COMMENT '停留开始时间',
  `end_time` datetime NOT NULL COMMENT '停留结束时间',
  `duration_sec` bigint NOT NULL COMMENT '停留时长（秒）',
  `city` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所在城市',
  `district` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '所在区县',
  `poi_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '兴趣点名称',
  `photo_cover_id` bigint NULL DEFAULT NULL COMMENT '代表性照片ID',
  `video_cover_id` bigint NULL DEFAULT NULL COMMENT '代表性视频 ID（新增）',
  `photo_count` int NOT NULL DEFAULT 0 COMMENT '地点内照片数量',
  `video_count` int NOT NULL DEFAULT 0 COMMENT '地点内视频数量（新增）',
  `privacy_level` enum('PUBLIC','MASKED','PRIVATE') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '隐私级别，可能值：PUBLIC, MASKED, PRIVATE',
  `generated_at` datetime NOT NULL COMMENT '生成时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `user_notes` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '用户针对该地点的笔记',
  `user_tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '逗号分隔的标签（如\"必吃,排队长\"）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_place_summary`(`user_id` ASC) USING BTREE,
  INDEX `fk_trip_place_summary`(`trip_id` ASC) USING BTREE,
  CONSTRAINT `fk_trip_place_summary` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_place_summary` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 130 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for place_summary_member
-- ----------------------------
DROP TABLE IF EXISTS `place_summary_member`;
CREATE TABLE `place_summary_member`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `trip_id` bigint NOT NULL,
  `place_summary_id` bigint NOT NULL,
  `member_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `member_id` bigint NOT NULL,
  `member_role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `score` float NULL DEFAULT NULL,
  `sort_index` int NULL DEFAULT NULL,
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_psm_trip`(`trip_id` ASC) USING BTREE,
  INDEX `idx_psm_place`(`place_summary_id` ASC) USING BTREE,
  INDEX `idx_psm_member`(`member_type` ASC, `member_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2758 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for story_block
-- ----------------------------
DROP TABLE IF EXISTS `story_block`;
CREATE TABLE `story_block`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '故事块ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `trip_id` bigint NOT NULL COMMENT '行程ID',
  `block_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '块类型',
  `ref_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `ref_id` bigint NULL DEFAULT NULL COMMENT '引用对象ID',
  `sort_time` datetime NOT NULL COMMENT '排序时间',
  `sort_index` int NOT NULL DEFAULT 0 COMMENT '同时间排序',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '标题',
  `text_content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `cover_object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '封面资源',
  `is_hidden` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否隐藏',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_story_block`(`user_id` ASC) USING BTREE,
  INDEX `fk_trip_story_block`(`trip_id` ASC) USING BTREE,
  CONSTRAINT `fk_trip_story_block` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_story_block` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 267 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for track_point
-- ----------------------------
DROP TABLE IF EXISTS `track_point`;
CREATE TABLE `track_point`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '轨迹点ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `trip_id` bigint NOT NULL COMMENT '归属行程ID',
  `ts` bigint NOT NULL COMMENT '采集时间戳（毫秒）',
  `lat_enc` varbinary(255) NULL DEFAULT NULL,
  `lng_enc` varbinary(255) NULL DEFAULT NULL,
  `accuracy_m` float NULL DEFAULT NULL COMMENT '定位精度（米）',
  `speed_mps` float NULL DEFAULT NULL COMMENT '速度（米/秒）',
  `heading_deg` float NULL DEFAULT NULL COMMENT '航向（度）',
  `source` enum('WX_BG','WX_FG','MANUAL','EXIF') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '轨迹点来源，可能值：WX_BG, WX_FG, MANUAL, EXIF',
  `raw_coord_type` enum('WGS84','GCJ02') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '坐标系类型，可能值：WGS84, GCJ02',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `segment_id` bigint NULL DEFAULT NULL COMMENT '所属轨迹分段ID',
  `render_eligible` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否参与轨迹绘制',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_track_point`(`user_id` ASC) USING BTREE,
  INDEX `fk_track_point_segment`(`segment_id` ASC) USING BTREE,
  INDEX `idx_track_point_trip_segment_ts`(`trip_id` ASC, `segment_id` ASC, `ts` ASC) USING BTREE,
  CONSTRAINT `fk_track_point_segment` FOREIGN KEY (`segment_id`) REFERENCES `trip_segment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_trip_track_point` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_track_point` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2456 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip
-- ----------------------------
DROP TABLE IF EXISTS `trip`;
CREATE TABLE `trip`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '行程ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户ID',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '行程标题',
  `status` enum('ACTIVE','PAUSED','PROCESSING','FINISHED') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '行程状态，可能值：ACTIVE, PAUSED, PROCESSING, FINISHED',
  `start_time` datetime NOT NULL COMMENT '行程开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '行程结束时间',
  `timezone` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `summary_text` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `privacy_mode` enum('PUBLIC','MASKED','PRIVATE') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '隐私模式',
  `distance_m` bigint NULL DEFAULT 0 COMMENT '行程总距离（米）',
  `duration_sec` bigint NULL DEFAULT 0 COMMENT '行程总持续时间（秒）',
  `photo_count` int NULL DEFAULT 0 COMMENT '行程内照片数量',
  `video_count` int NULL DEFAULT 0 COMMENT '行程内视频数量',
  `generated_at` datetime NULL DEFAULT NULL COMMENT '行程生成时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_trip`(`user_id` ASC) USING BTREE,
  CONSTRAINT `fk_user_trip` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip_ai_summary
-- ----------------------------
DROP TABLE IF EXISTS `trip_ai_summary`;
CREATE TABLE `trip_ai_summary`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'AI总结ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `trip_id` bigint NOT NULL COMMENT '行程ID',
  `overview` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `highlights` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `best_moment` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `route_summary` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL,
  `model_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `version` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `generated_at` datetime NOT NULL COMMENT '生成时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `is_latest` bit(1) NULL DEFAULT NULL,
  `regenerate_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `user_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '用户自定义写作风格提示词，NULL 表示使用系统默认提示词',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_trip_ai_summary`(`user_id` ASC) USING BTREE,
  INDEX `fk_trip_trip_ai_summary`(`trip_id` ASC) USING BTREE,
  CONSTRAINT `fk_trip_trip_ai_summary` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_trip_ai_summary` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 127 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip_bbox
-- ----------------------------
DROP TABLE IF EXISTS `trip_bbox`;
CREATE TABLE `trip_bbox`  (
  `trip_id` bigint NOT NULL COMMENT '行程ID，主键',
  `min_lat` float NOT NULL COMMENT '最小纬度',
  `min_lng` float NOT NULL COMMENT '最小经度',
  `max_lat` float NOT NULL COMMENT '最大纬度',
  `max_lng` float NOT NULL COMMENT '最大经度',
  PRIMARY KEY (`trip_id`) USING BTREE,
  CONSTRAINT `fk_trip_trip_bbox` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip_note
-- ----------------------------
DROP TABLE IF EXISTS `trip_note`;
CREATE TABLE `trip_note`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '笔记ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `trip_id` bigint NOT NULL COMMENT '行程ID',
  `anchor_ts` bigint NULL DEFAULT NULL COMMENT '关联时间戳',
  `lat_enc` varbinary(255) NULL DEFAULT NULL,
  `lng_enc` varbinary(255) NULL DEFAULT NULL,
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '标题',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `privacy_mode` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `coord_type` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `coordinate_source` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `location_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `fk_user_trip_note`(`user_id` ASC) USING BTREE,
  INDEX `fk_trip_trip_note`(`trip_id` ASC) USING BTREE,
  CONSTRAINT `fk_trip_trip_note` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_trip_note` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip_route_snapshot
-- ----------------------------
DROP TABLE IF EXISTS `trip_route_snapshot`;
CREATE TABLE `trip_route_snapshot`  (
  `trip_id` bigint NOT NULL COMMENT '行程ID，主键',
  `route_status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `algo_version` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '匹配算法版本',
  `fingerprint` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '生成该快照时的轨迹指纹',
  `point_count` int NOT NULL DEFAULT 0 COMMENT '参与匹配的轨迹点数',
  `start_ts` bigint NULL DEFAULT NULL COMMENT '起始时间戳',
  `end_ts` bigint NULL DEFAULT NULL COMMENT '结束时间戳',
  `overview_polyline_json` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '简化/展示用路线JSON',
  `oss_object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'OSS对象路径',
  `oss_etag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'OSS ETag',
  `content_hash` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '快照内容哈希',
  `generated_at` datetime NOT NULL COMMENT '快照生成时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  `media_point_count` int NOT NULL DEFAULT 0 COMMENT '参与路线辅助的媒体点数',
  `segment_count` int NOT NULL DEFAULT 0 COMMENT '参与绘制的轨迹分段数',
  PRIMARY KEY (`trip_id`) USING BTREE,
  INDEX `idx_trip_route_snapshot_status`(`route_status` ASC) USING BTREE,
  INDEX `idx_trip_route_snapshot_algo`(`algo_version` ASC) USING BTREE,
  CONSTRAINT `fk_trip_route_snapshot_trip` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for trip_segment
-- ----------------------------
DROP TABLE IF EXISTS `trip_segment`;
CREATE TABLE `trip_segment`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '分段ID',
  `trip_id` bigint NOT NULL COMMENT '行程ID',
  `segment_no` int NOT NULL COMMENT '分段序号，从1开始',
  `start_ts` bigint NOT NULL COMMENT '分段开始时间戳（毫秒）',
  `end_ts` bigint NULL DEFAULT NULL COMMENT '分段结束时间戳（毫秒）',
  `start_reason` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `end_reason` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `is_closed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已闭合',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_trip_segment_no`(`trip_id` ASC, `segment_no` ASC) USING BTREE,
  INDEX `idx_trip_segment_trip_time`(`trip_id` ASC, `start_ts` ASC, `end_ts` ASC) USING BTREE,
  CONSTRAINT `fk_trip_segment_trip` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID，主键',
  `open_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '微信OpenID，唯一标识用户',
  `union_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '可选，多个小程序/公众号共享',
  `nickname` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户昵称',
  `avatar_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户头像URL',
  `default_privacy_mode` enum('PUBLIC','MASKED','PRIVATE') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '默认隐私模式',
  `privacy_agreement_accepted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已同意当前隐私协议',
  `privacy_agreement_version` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `privacy_agreement_accepted_at` datetime NULL DEFAULT NULL COMMENT '同意隐私协议的时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `open_id`(`open_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for video
-- ----------------------------
DROP TABLE IF EXISTS `video`;
CREATE TABLE `video`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '视频 ID，主键',
  `user_id` bigint NOT NULL COMMENT '所属用户 ID',
  `trip_id` bigint NOT NULL COMMENT '归属行程ID',
  `object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '对象存储路径（唯一）',
  `file_hash` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '文件哈希值，用于去重',
  `thumbnail_object_key` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '缩略图对象存储路径',
  `duration_sec` int NULL DEFAULT NULL COMMENT '视频时长（秒）',
  `file_size` bigint NULL DEFAULT NULL COMMENT '文件大小（字节）',
  `resolution` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `shot_time_exif` datetime NULL DEFAULT NULL COMMENT 'Exif 拍摄时间',
  `lat_enc` varbinary(255) NULL DEFAULT NULL,
  `lng_enc` varbinary(255) NULL DEFAULT NULL,
  `created_at` datetime NOT NULL COMMENT '上传时间',
  `user_caption` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '用户添加的视频说明',
  `privacy_mode` enum('PUBLIC','MASKED','PRIVATE') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '覆盖行程默认设置的个体隐私级别',
  `processing_status` enum('PENDING','PROCESSING','COMPLETED','FAILED') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'PENDING' COMMENT '处理状态',
  `capture_ts_override` bigint NULL DEFAULT NULL COMMENT '用户修正后的拍摄时间戳',
  `capture_lat_override` varbinary(255) NULL DEFAULT NULL,
  `capture_lng_override` varbinary(255) NULL DEFAULT NULL,
  `binding_status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `binding_score` float NULL DEFAULT NULL COMMENT '媒体归属评分',
  `capture_time_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'EXIF/USER_INPUT/UPLOAD_TIME',
  `capture_coord_source` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'EXIF/MANUAL/NONE',
  `capture_coord_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '媒体坐标类型：WGS84 / GCJ02',
  `location_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `note_id` bigint NULL DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `object_key`(`object_key` ASC) USING BTREE,
  INDEX `fk_user_video`(`user_id` ASC) USING BTREE,
  INDEX `idx_video_trip_binding`(`trip_id` ASC, `binding_status` ASC, `shot_time_exif` ASC) USING BTREE,
  CONSTRAINT `fk_trip_video` FOREIGN KEY (`trip_id`) REFERENCES `trip` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_user_video` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
