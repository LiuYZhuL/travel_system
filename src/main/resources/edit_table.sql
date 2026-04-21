-- =========================
-- 1. 行程分段表：支持暂停/恢复/结束后的多段轨迹
-- =========================
CREATE TABLE IF NOT EXISTS trip_segment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分段ID',
  trip_id BIGINT NOT NULL COMMENT '行程ID',
  segment_no INT NOT NULL COMMENT '分段序号，从1开始',
  start_ts BIGINT NOT NULL COMMENT '分段开始时间戳（毫秒）',
  end_ts BIGINT DEFAULT NULL COMMENT '分段结束时间戳（毫秒）',
  start_reason ENUM('TRIP_START','RESUME') NOT NULL COMMENT '开始原因',
  end_reason ENUM('PAUSE','FINISH') DEFAULT NULL COMMENT '结束原因',
  is_closed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已闭合',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  CONSTRAINT fk_trip_segment_trip FOREIGN KEY (trip_id) REFERENCES trip(id),
  UNIQUE KEY uk_trip_segment_no (trip_id, segment_no),
  KEY idx_trip_segment_trip_time (trip_id, start_ts, end_ts)
);

-- =========================
-- 2. TrackPoint：增加 segment_id 与 render_eligible
-- paused 期间的点可以保存，但不参与绘制
-- =========================
ALTER TABLE track_point
  ADD COLUMN segment_id BIGINT DEFAULT NULL COMMENT '所属轨迹分段ID',
  ADD COLUMN render_eligible BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否参与轨迹绘制',
  ADD CONSTRAINT fk_track_point_segment FOREIGN KEY (segment_id) REFERENCES trip_segment(id);

CREATE INDEX idx_track_point_trip_segment_ts ON track_point(trip_id, segment_id, ts);

-- =========================
-- 3. Photo / Video：增加手动修正时间/位置，以及归属判定状态
-- =========================
ALTER TABLE photo
  ADD COLUMN note_id BIGINT DEFAULT NULL COMMENT '关联的笔记 ID',
  ADD COLUMN capture_ts_override BIGINT DEFAULT NULL COMMENT '用户修正后的拍摄时间戳',
  ADD COLUMN capture_lat_override BINARY(16) DEFAULT NULL COMMENT '用户修正后的拍摄纬度',
  ADD COLUMN capture_lng_override BINARY(16) DEFAULT NULL COMMENT '用户修正后的拍摄经度',
  ADD COLUMN location_name VARCHAR(255) DEFAULT NULL COMMENT '用户确认后的地点名称',
  ADD COLUMN binding_status ENUM('PENDING','IN_TRIP','OUT_OF_TRIP','MANUAL_CONFIRMED') NOT NULL DEFAULT 'PENDING' COMMENT '媒体是否属于本次行程',
  ADD COLUMN binding_score FLOAT DEFAULT NULL COMMENT '媒体归属评分',
  ADD COLUMN capture_time_source VARCHAR(32) DEFAULT NULL COMMENT 'EXIF/USER_INPUT/UPLOAD_TIME',
  ADD COLUMN capture_coord_source VARCHAR(32) DEFAULT NULL COMMENT 'EXIF/MANUAL/NONE';

ALTER TABLE video
  ADD COLUMN note_id BIGINT DEFAULT NULL COMMENT '关联的笔记 ID',
  ADD COLUMN capture_ts_override BIGINT DEFAULT NULL COMMENT '用户修正后的拍摄时间戳',
  ADD COLUMN capture_lat_override BINARY(16) DEFAULT NULL COMMENT '用户修正后的拍摄纬度',
  ADD COLUMN capture_lng_override BINARY(16) DEFAULT NULL COMMENT '用户修正后的拍摄经度',
  ADD COLUMN location_name VARCHAR(255) DEFAULT NULL COMMENT '用户确认后的地点名称',
  ADD COLUMN binding_status ENUM('PENDING','IN_TRIP','OUT_OF_TRIP','MANUAL_CONFIRMED') NOT NULL DEFAULT 'PENDING' COMMENT '媒体是否属于本次行程',
  ADD COLUMN binding_score FLOAT DEFAULT NULL COMMENT '媒体归属评分',
  ADD COLUMN capture_time_source VARCHAR(32) DEFAULT NULL COMMENT 'EXIF/USER_INPUT/UPLOAD_TIME',
  ADD COLUMN capture_coord_source VARCHAR(32) DEFAULT NULL COMMENT 'EXIF/MANUAL/NONE';

CREATE INDEX idx_photo_trip_binding ON photo(trip_id, binding_status, shot_time_exif);
CREATE INDEX idx_video_trip_binding ON video(trip_id, binding_status, shot_time_exif);
CREATE INDEX idx_photo_note_id ON photo(note_id);
CREATE INDEX idx_video_note_id ON video(note_id);

-- =========================
-- 4. Anchor：允许 pending/out_of_trip 时没有最终投影点
-- 增加 route_eligible / segment_id / media_ts
-- =========================
ALTER TABLE anchor
  MODIFY COLUMN lat_enc BINARY(16) DEFAULT NULL COMMENT '最终匹配纬度，可为空（待确认/非本行程时）',
  MODIFY COLUMN lng_enc BINARY(16) DEFAULT NULL COMMENT '最终匹配经度，可为空（待确认/非本行程时）',
  ADD COLUMN media_ts BIGINT DEFAULT NULL COMMENT '媒体最终采用的排序时间戳',
  ADD COLUMN segment_id BIGINT DEFAULT NULL COMMENT '所属轨迹分段ID',
  ADD COLUMN route_eligible BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否允许进入轨迹辅助',
  ADD COLUMN projection_status ENUM('PENDING','PROJECTED','OUT_OF_TRIP','MANUAL_FIXED') NOT NULL DEFAULT 'PENDING' COMMENT '投影状态',
  ADD CONSTRAINT fk_anchor_segment FOREIGN KEY (segment_id) REFERENCES trip_segment(id);

CREATE INDEX idx_anchor_trip_segment_ts ON anchor(trip_id, segment_id, media_ts);
CREATE INDEX idx_anchor_trip_route_eligible ON anchor(trip_id, route_eligible, projection_status);

-- =========================
-- 5. 路线快照：增加媒体/分段统计
-- 结束后补传“属于本次行程”的媒体时可以判断是否需要重算
-- =========================
ALTER TABLE trip_route_snapshot
  ADD COLUMN media_point_count INT NOT NULL DEFAULT 0 COMMENT '参与路线辅助的媒体点数',
  ADD COLUMN segment_count INT NOT NULL DEFAULT 0 COMMENT '参与绘制的轨迹分段数';
