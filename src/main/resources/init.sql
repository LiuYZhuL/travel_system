-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS travel_system;

-- 使用创建的数据库
USE travel_system;

-- 创建用户表（User 表）
CREATE TABLE IF NOT EXISTS user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID，主键',
  open_id VARCHAR(255) NOT NULL UNIQUE COMMENT '微信OpenID，唯一标识用户',
  union_id VARCHAR(255) DEFAULT NULL COMMENT '可选，多个小程序/公众号共享',
  nickname VARCHAR(255) DEFAULT NULL COMMENT '用户昵称',
  avatar_url VARCHAR(255) DEFAULT NULL COMMENT '用户头像URL',
  default_privacy_mode ENUM('PUBLIC', 'MASKED', 'PRIVATE') NOT NULL COMMENT '默认隐私模式',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间'
);

-- 创建行程表（Trip 表）
CREATE TABLE IF NOT EXISTS trip (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程ID，主键',
  user_id BIGINT NOT NULL COMMENT '所属用户ID',
  title VARCHAR(255) COMMENT '行程标题',
  status ENUM('ACTIVE', 'PAUSED', 'PROCESSING', 'FINISHED') NOT NULL COMMENT '行程状态，可能值：ACTIVE, PAUSED, PROCESSING, FINISHED',
  start_time DATETIME NOT NULL COMMENT '行程开始时间',
  end_time DATETIME DEFAULT NULL COMMENT '行程结束时间',
  timezone VARCHAR(50) NOT NULL COMMENT '时区',
  summary_text TEXT DEFAULT NULL COMMENT '行程摘要',
  privacy_mode ENUM('PUBLIC', 'MASKED', 'PRIVATE') NOT NULL COMMENT '隐私模式',
  distance_m BIGINT DEFAULT 0 COMMENT '行程总距离（米）',
  duration_sec BIGINT DEFAULT 0 COMMENT '行程总持续时间（秒）',
  photo_count INT DEFAULT 0 COMMENT '行程内照片数量',
  video_count INT DEFAULT 0 COMMENT '行程内视频数量',
  generated_at DATETIME DEFAULT NULL COMMENT '行程生成时间',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间'
);

-- 创建轨迹点表（TrackPoint 表）
CREATE TABLE IF NOT EXISTS track_point (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '轨迹点ID，主键',
  user_id BIGINT NOT NULL COMMENT '所属用户ID',
  trip_id BIGINT NOT NULL COMMENT '归属行程ID',
  ts BIGINT NOT NULL COMMENT '采集时间戳（毫秒）',
  lat_enc BINARY(16) NOT NULL COMMENT '加密纬度',
  lng_enc BINARY(16) NOT NULL COMMENT '加密经度',
  accuracy_m FLOAT DEFAULT NULL COMMENT '定位精度（米）',
  speed_mps FLOAT DEFAULT NULL COMMENT '速度（米/秒）',
  heading_deg FLOAT DEFAULT NULL COMMENT '航向（度）',
  source ENUM('WX_BG', 'WX_FG', 'MANUAL', 'EXIF') NOT NULL COMMENT '轨迹点来源，可能值：WX_BG, WX_FG, MANUAL, EXIF',
  raw_coord_type ENUM('WGS84', 'GCJ02') NOT NULL COMMENT '坐标系类型，可能值：WGS84, GCJ02',
  created_at DATETIME NOT NULL COMMENT '创建时间'
);

-- 创建照片表（Photo 表）
CREATE TABLE IF NOT EXISTS photo (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '照片ID，主键',
  user_id BIGINT NOT NULL COMMENT '所属用户ID',
  trip_id BIGINT NOT NULL COMMENT '归属行程ID',
  object_key VARCHAR(255) NOT NULL UNIQUE COMMENT '对象存储路径（唯一）',
  file_hash VARCHAR(255) DEFAULT NULL COMMENT '文件哈希值，用于去重',
  shot_time_exif DATETIME DEFAULT NULL COMMENT 'Exif拍摄时间',
  lat_enc BINARY(16) DEFAULT NULL COMMENT 'Exif加密纬度',
  lng_enc BINARY(16) DEFAULT NULL COMMENT 'Exif加密经度',
  created_at DATETIME NOT NULL COMMENT '上传时间',
  user_caption VARCHAR(500) DEFAULT NULL COMMENT '用户添加的照片说明',
  privacy_mode ENUM('PUBLIC', 'MASKED', 'PRIVATE') DEFAULT NULL COMMENT '覆盖行程默认设置的个体隐私级别',
  is_cover BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否作为行程封面展示'
);

-- 创建视频表（Video 表）- 新增
CREATE TABLE IF NOT EXISTS video (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '视频 ID，主键',
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    trip_id BIGINT NOT NULL COMMENT '归属行程ID',
    object_key VARCHAR(255) NOT NULL UNIQUE COMMENT '对象存储路径（唯一）',
    file_hash VARCHAR(255) DEFAULT NULL COMMENT '文件哈希值，用于去重',
    thumbnail_object_key VARCHAR(255) DEFAULT NULL COMMENT '缩略图对象存储路径',
    duration_sec INT DEFAULT NULL COMMENT '视频时长（秒）',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小（字节）',
    resolution VARCHAR(20) DEFAULT NULL COMMENT '视频分辨率（如"1920x1080"）',
    shot_time_exif DATETIME DEFAULT NULL COMMENT 'Exif 拍摄时间',
    lat_enc BINARY(16) DEFAULT NULL COMMENT 'Exif 加密纬度',
    lng_enc BINARY(16) DEFAULT NULL COMMENT 'Exif 加密经度',
    created_at DATETIME NOT NULL COMMENT '上传时间',
    user_caption VARCHAR(500) DEFAULT NULL COMMENT '用户添加的视频说明',
    privacy_mode ENUM('PUBLIC', 'MASKED', 'PRIVATE') DEFAULT NULL COMMENT '覆盖行程默认设置的个体隐私级别',
    processing_status ENUM('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '处理状态'
    );

-- 创建照片锚点表（Anchor 表）
CREATE TABLE IF NOT EXISTS anchor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '锚点 ID，主键',
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    trip_id BIGINT NOT NULL COMMENT '归属行程ID',
    photo_id BIGINT DEFAULT NULL COMMENT '关联照片ID（与 video_id 至少一个不为 NULL）',
    video_id BIGINT DEFAULT NULL COMMENT '关联视频 ID（新增，与 photo_id 至少一个不为 NULL）',
    matched_ts BIGINT DEFAULT NULL COMMENT '匹配到的轨迹时间戳',
    lat_enc BINARY(16) NOT NULL COMMENT '最终匹配的加密纬度',
    lng_enc BINARY(16) NOT NULL COMMENT '最终匹配的加密经度',
    match_method ENUM('EXIF_DIRECT', 'TIME_NEAREST', 'INTERPOLATE', 'MANUAL_PICK') NOT NULL COMMENT '匹配方法，可能值：EXIF_DIRECT, TIME_NEAREST, INTERPOLATE, MANUAL_PICK',
    time_delta_sec INT DEFAULT NULL COMMENT '时间差（秒）',
    confidence FLOAT NOT NULL COMMENT '匹配置信度（0~1）',
    manual_override BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否手动校准过',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    CONSTRAINT chk_anchor_media CHECK (photo_id IS NOT NULL OR video_id IS NOT NULL)
    );

-- 创建地点摘要表（PlaceSummary 表）
CREATE TABLE IF NOT EXISTS place_summary (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '地点摘要ID，主键',
  user_id BIGINT NOT NULL COMMENT '所属用户ID',
  trip_id BIGINT NOT NULL COMMENT '归属行程ID',
  center_lat_enc BINARY(16) NOT NULL COMMENT '地点中心加密纬度',
  center_lng_enc BINARY(16) NOT NULL COMMENT '地点中心加密经度',
  geohash VARCHAR(20) DEFAULT NULL COMMENT '地点Geohash编码',
  start_time DATETIME NOT NULL COMMENT '停留开始时间',
  end_time DATETIME NOT NULL COMMENT '停留结束时间',
  duration_sec BIGINT NOT NULL COMMENT '停留时长（秒）',
  city VARCHAR(255) NOT NULL COMMENT '所在城市',
  district VARCHAR(255) DEFAULT NULL COMMENT '所在区县',
  poi_name VARCHAR(255) NOT NULL COMMENT '兴趣点名称',
  photo_cover_id BIGINT DEFAULT NULL COMMENT '代表性照片ID',
  video_cover_id BIGINT DEFAULT NULL COMMENT '代表性视频 ID（新增）',
  photo_count INT NOT NULL DEFAULT 0 COMMENT '地点内照片数量',
  video_count INT NOT NULL DEFAULT 0 COMMENT '地点内视频数量（新增）',
  privacy_level ENUM('PUBLIC', 'MASKED', 'PRIVATE') NOT NULL COMMENT '隐私级别，可能值：PUBLIC, MASKED, PRIVATE',
  generated_at DATETIME NOT NULL COMMENT '生成时间',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  user_notes TEXT DEFAULT NULL COMMENT '用户针对该地点的笔记',
  user_tags VARCHAR(255) DEFAULT NULL COMMENT '逗号分隔的标签（如"必吃,排队长"）'
);

-- 创建行程包围盒表（TripBBox 表）
CREATE TABLE IF NOT EXISTS trip_bbox (
  trip_id BIGINT NOT NULL PRIMARY KEY COMMENT '行程ID，主键',
  min_lat FLOAT NOT NULL COMMENT '最小纬度',
  min_lng FLOAT NOT NULL COMMENT '最小经度',
  max_lat FLOAT NOT NULL COMMENT '最大纬度',
  max_lng FLOAT NOT NULL COMMENT '最大经度'
);

CREATE TABLE trip_note (
                           id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '笔记ID',
                           user_id BIGINT NOT NULL COMMENT '用户ID',
                           trip_id BIGINT NOT NULL COMMENT '行程ID',
                           anchor_ts BIGINT DEFAULT NULL COMMENT '关联时间戳',
                           lat_enc BINARY(16) DEFAULT NULL COMMENT '关联纬度',
                           lng_enc BINARY(16) DEFAULT NULL COMMENT '关联经度',
                           title VARCHAR(255) DEFAULT NULL COMMENT '标题',
                           content TEXT NOT NULL COMMENT '正文',
                           privacy_mode ENUM('PUBLIC', 'MASKED', 'PRIVATE') NOT NULL DEFAULT 'PUBLIC' COMMENT '隐私模式',
                           created_at DATETIME NOT NULL COMMENT '创建时间',
                           updated_at DATETIME NOT NULL COMMENT '更新时间'
);

CREATE TABLE story_block (
                             id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '故事块ID',
                             user_id BIGINT NOT NULL COMMENT '用户ID',
                             trip_id BIGINT NOT NULL COMMENT '行程ID',
                             block_type VARCHAR(50) NOT NULL COMMENT '块类型',
                             ref_type VARCHAR(50) DEFAULT NULL COMMENT '引用对象类型',
                             ref_id BIGINT DEFAULT NULL COMMENT '引用对象ID',
                             sort_time DATETIME NOT NULL COMMENT '排序时间',
                             sort_index INT NOT NULL DEFAULT 0 COMMENT '同时间排序',
                             title VARCHAR(255) DEFAULT NULL COMMENT '标题',
                             text_content TEXT DEFAULT NULL COMMENT '块文本',
                             cover_object_key VARCHAR(255) DEFAULT NULL COMMENT '封面资源',
                             is_hidden BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否隐藏',
                             created_at DATETIME NOT NULL COMMENT '创建时间',
                             updated_at DATETIME NOT NULL COMMENT '更新时间'
);

CREATE TABLE trip_ai_summary (
                                 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'AI总结ID',
                                 user_id BIGINT NOT NULL COMMENT '用户ID',
                                 trip_id BIGINT NOT NULL COMMENT '行程ID',
                                 overview TEXT NOT NULL COMMENT '总览摘要',
                                 highlights JSON DEFAULT NULL COMMENT '亮点列表',
                                 best_moment TEXT DEFAULT NULL COMMENT '最佳时刻',
                                 route_summary TEXT DEFAULT NULL COMMENT '路线总结',
                                 model_name VARCHAR(100) DEFAULT NULL COMMENT '模型名称',
                                 version VARCHAR(50) DEFAULT NULL COMMENT '版本',
                                 generated_at DATETIME NOT NULL COMMENT '生成时间',
                                 created_at DATETIME NOT NULL COMMENT '创建时间',
                                 updated_at DATETIME NOT NULL COMMENT '更新时间'
);

-- 为表添加外键约束
ALTER TABLE trip
  ADD CONSTRAINT fk_user_trip FOREIGN KEY (user_id) REFERENCES user(id);

ALTER TABLE track_point
  ADD CONSTRAINT fk_user_track_point FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_track_point FOREIGN KEY (trip_id) REFERENCES trip(id);


ALTER TABLE photo
    ADD CONSTRAINT fk_user_photo FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_photo FOREIGN KEY (trip_id) REFERENCES trip(id);

-- 为视频表添加外键约束 - 新增
ALTER TABLE video
    ADD CONSTRAINT fk_user_video FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_video FOREIGN KEY (trip_id) REFERENCES trip(id);

ALTER TABLE anchor
    ADD CONSTRAINT fk_user_anchor FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_anchor FOREIGN KEY (trip_id) REFERENCES trip(id),
  ADD CONSTRAINT fk_photo_anchor FOREIGN KEY (photo_id) REFERENCES photo(id),
  ADD CONSTRAINT fk_video_anchor FOREIGN KEY (video_id) REFERENCES video(id);

ALTER TABLE place_summary
  ADD CONSTRAINT fk_user_place_summary FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_place_summary FOREIGN KEY (trip_id) REFERENCES trip(id);

ALTER TABLE trip_bbox
  ADD CONSTRAINT fk_trip_trip_bbox FOREIGN KEY (trip_id) REFERENCES trip(id);

ALTER TABLE trip_note
  ADD CONSTRAINT fk_user_trip_note FOREIGN KEY (user_id) REFERENCES user(id),
    ADD CONSTRAINT fk_trip_trip_note FOREIGN KEY (trip_id) REFERENCES trip(id);

ALTER TABLE story_block
  ADD CONSTRAINT fk_user_story_block FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_story_block FOREIGN KEY (trip_id) REFERENCES trip(id);

ALTER TABLE trip_ai_summary
  ADD CONSTRAINT fk_user_trip_ai_summary FOREIGN KEY (user_id) REFERENCES user(id),
  ADD CONSTRAINT fk_trip_trip_ai_summary FOREIGN KEY (trip_id) REFERENCES trip(id);
