-- Phase 2：发布状态迁入管理库（release 标记无法从 git 重建）
-- 投影层 version.stage 改为重建时按本表重放的派生值。
CREATE TABLE release (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    path VARCHAR(512) NOT NULL,
    spec_hash VARCHAR(128) NOT NULL,
    released_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_release_repo_path (repo_id, path),
    KEY idx_release_repo (repo_id),
    CONSTRAINT fk_release_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
