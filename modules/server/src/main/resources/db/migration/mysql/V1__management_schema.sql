-- Phase 2 管理库 schema（MySQL 8.4 LTS）
-- 时间统一 UTC：DATETIME(6) + JVM -Duser.timezone=UTC（spike 实测必需）。
-- token_hash 用 utf8mb4_bin 区分大小写；username 默认 utf8mb4_0900_ai_ci（不敏感唯一）。

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_project_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE repo (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    url TEXT NOT NULL,
    branch VARCHAR(128) NOT NULL DEFAULT 'main',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'new',
    last_error TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_repo_project_url (project_id, url),
    KEY idx_repo_url (url),
    CONSTRAINT fk_repo_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    token_hash VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    is_platform_admin TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE membership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    -- project 数据迁入管理库后补 FK；当前 project 仍由投影层承载（逻辑引用）
    project_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    UNIQUE KEY uk_membership_user_project (user_id, project_id),
    KEY idx_membership_project (project_id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    api_id BIGINT,
    doc_id BIGINT,
    method VARCHAR(16) NOT NULL DEFAULT '',
    path TEXT NOT NULL,
    author VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_comment_repo (repo_id, created_at),
    KEY idx_comment_api (api_id, created_at),
    CONSTRAINT fk_comment_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE subscription (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    api_id BIGINT,
    method VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    team VARCHAR(128) NOT NULL,
    notify_url TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_subscription_endpoint (repo_id, method, path, team),
    KEY idx_subscription_lookup (repo_id, method, path),
    CONSTRAINT fk_subscription_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    team VARCHAR(128) NOT NULL,
    repo_id BIGINT NOT NULL,
    method VARCHAR(16) NOT NULL,
    path TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_notification_team (team, created_at),
    CONSTRAINT fk_notification_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    path TEXT NOT NULL,
    new_content LONGTEXT NOT NULL,
    diff LONGTEXT NOT NULL,
    old_hash VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    requester VARCHAR(128) NOT NULL,
    reviewer VARCHAR(128),
    reject_reason TEXT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6),
    KEY idx_review_repo_status (repo_id, status),
    CONSTRAINT fk_review_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mock_rule (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    api_id BIGINT,
    field_pattern TEXT NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    rule_value TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_mock_rule_repo (repo_id),
    CONSTRAINT fk_mock_rule_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE changelog (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT,
    api_id BIGINT,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    detail TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_changelog_repo (repo_id, created_at),
    CONSTRAINT fk_changelog_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
