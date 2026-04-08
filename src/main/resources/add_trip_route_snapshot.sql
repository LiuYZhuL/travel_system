CREATE TABLE IF NOT EXISTS trip_route_snapshot (
  trip_id BIGINT NOT NULL PRIMARY KEY COMMENT '行程ID，主键',
  route_status ENUM('PROCESSING','FINAL','FAILED') NOT NULL DEFAULT 'FINAL' COMMENT '路线快照状态',
  algo_version VARCHAR(50) NOT NULL COMMENT '匹配算法版本',
  fingerprint VARCHAR(255) NOT NULL COMMENT '生成该快照时的轨迹指纹',
  point_count INT NOT NULL DEFAULT 0 COMMENT '参与匹配的轨迹点数',
  start_ts BIGINT DEFAULT NULL COMMENT '起始时间戳',
  end_ts BIGINT DEFAULT NULL COMMENT '结束时间戳',
  overview_polyline_json LONGTEXT DEFAULT NULL COMMENT '简化/展示用路线JSON',
  oss_object_key VARCHAR(255) DEFAULT NULL COMMENT 'OSS对象路径',
  oss_etag VARCHAR(255) DEFAULT NULL COMMENT 'OSS ETag',
  content_hash VARCHAR(128) DEFAULT NULL COMMENT '快照内容哈希',
  generated_at DATETIME NOT NULL COMMENT '快照生成时间',
  created_at DATETIME NOT NULL COMMENT '创建时间',
  updated_at DATETIME NOT NULL COMMENT '更新时间',
  CONSTRAINT fk_trip_route_snapshot_trip FOREIGN KEY (trip_id) REFERENCES trip(id),
  KEY idx_trip_route_snapshot_status (route_status),
  KEY idx_trip_route_snapshot_algo (algo_version)
);
