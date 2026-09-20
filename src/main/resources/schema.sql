CREATE TABLE IF NOT EXISTS migration_setting (
  id INT PRIMARY KEY,
  source_jdbc_url VARCHAR(1000),
  source_username VARCHAR(255),
  source_password VARCHAR(1000),
  target_base_url VARCHAR(1000),
  target_token VARCHAR(4000),
  target_operator VARCHAR(255),
  target_username VARCHAR(255),
  target_password VARCHAR(1000),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO migration_setting(id, source_jdbc_url, source_username, source_password, target_base_url, target_token, target_operator)
VALUES (1,
  'jdbc:mysql://127.0.0.1:3306/dolphinscheduler?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai',
  'root', '', 'http://127.0.0.1:8080', '', 'admin')
ON DUPLICATE KEY UPDATE id=id;

CREATE TABLE IF NOT EXISTS migration_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  operation VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  phase VARCHAR(64) NOT NULL,
  progress INT DEFAULT 0,
  source_version VARCHAR(32) DEFAULT '3.1.9',
  dry_run BOOLEAN DEFAULT TRUE,
  total_objects INT DEFAULT 0,
  processed_objects INT DEFAULT 0,
  success_count INT DEFAULT 0,
  warning_count INT DEFAULT 0,
  failure_count INT DEFAULT 0,
  message VARCHAR(2000),
  summary_json LONGTEXT,
  cancel_requested BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  started_at TIMESTAMP NULL,
  finished_at TIMESTAMP NULL,
  KEY idx_migration_run_status(status),
  KEY idx_migration_run_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS migration_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  object_type VARCHAR(64) NOT NULL,
  source_code VARCHAR(128),
  source_version INT,
  object_name VARCHAR(512),
  task_type VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  progress INT DEFAULT 0,
  target_type VARCHAR(64),
  target_id VARCHAR(128),
  message VARCHAR(2000),
  payload_json LONGTEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_migration_item_run(run_id),
  KEY idx_migration_item_status(run_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS migration_issue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  item_id BIGINT,
  severity VARCHAR(16) NOT NULL,
  issue_code VARCHAR(128) NOT NULL,
  object_type VARCHAR(64),
  source_code VARCHAR(128),
  object_name VARCHAR(512),
  message VARCHAR(2000),
  detail LONGTEXT,
  status VARCHAR(32) DEFAULT 'OPEN',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMP NULL,
  KEY idx_migration_issue_run(run_id),
  KEY idx_migration_issue_status(run_id,status,severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS migration_object_map (
  source_type VARCHAR(64) NOT NULL,
  source_code VARCHAR(128) NOT NULL,
  source_version INT NOT NULL DEFAULT 0,
  target_type VARCHAR(64) NOT NULL,
  target_id VARCHAR(128) NOT NULL,
  target_name VARCHAR(512),
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(source_type, source_code, source_version, target_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS migration_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  level VARCHAR(16) NOT NULL,
  phase VARCHAR(64),
  message VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_migration_event_run(run_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
