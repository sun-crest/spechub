-- Phase C（C1）：身份与审计基础（MySQL 8.4）
--
-- 与 PostgreSQL 版本保持一致：V6 只加列 / 建表 / 回填，旧代码仍可启动与写入；
-- C2–C4 代码切换后由 V7 删除 token_hash / is_platform_admin；
-- 会话不落管理库（阶段 C 直接使用 Spring Session Data Redis）。

-- ── 1. app_user 身份列 ────────────────────────────────────────────────────
ALTER TABLE app_user
    ADD COLUMN email VARCHAR(255) NULL,
    ADD COLUMN password_hash VARCHAR(255) NULL,
    ADD COLUMN password_updated_at DATETIME(6) NULL,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'active',
    ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN mfa_enabled TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN last_login_at DATETIME(6) NULL,
    -- 旧 token_hash 在 C2 停止写入后允许为空；V7 再删列。
    MODIFY COLUMN token_hash VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD CONSTRAINT chk_app_user_status CHECK (status IN ('active', 'disabled', 'invited'));

-- 邮箱唯一、忽略大小写（函数索引）；允许 NULL。Service 层写入前统一 trim().lowercase()。
CREATE UNIQUE INDEX uk_app_user_email_lower ON app_user ((LOWER(email)));

-- ── 2. user_token（用户 API 令牌）──────────────────────────────────────────
-- 迁移前检查：token_hash 理论上不应重复；若有则 CHECK 约束失败、迁移中止，
-- 避免 user_token.token_hash 唯一键静默丢凭据（需人工处理后再重试）。
CREATE TEMPORARY TABLE tmp_v6_token_guard (
    id TINYINT NOT NULL PRIMARY KEY,
    duplicate_hashes BIGINT NOT NULL,
    CONSTRAINT chk_v6_no_duplicate_token_hash CHECK (duplicate_hashes = 0)
);
INSERT INTO tmp_v6_token_guard (id, duplicate_hashes)
SELECT 1, COUNT(*)
FROM (
    SELECT token_hash
    FROM app_user
    WHERE token_hash IS NOT NULL
    GROUP BY token_hash
    HAVING COUNT(*) > 1
) dup;
DROP TEMPORARY TABLE tmp_v6_token_guard;

CREATE TABLE user_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    token_prefix VARCHAR(32) NOT NULL DEFAULT '',
    token_hash VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NULL,
    used_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_user_token_hash (token_hash),
    KEY idx_user_token_user (user_id, created_at),
    CONSTRAINT fk_user_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_user_token_purpose CHECK (purpose IN ('legacy', 'api', 'invite', 'password_reset')),
    -- legacy 行来自旧 app_user.token_hash，无过期信息；其余用途必须有过期时间。
    CONSTRAINT chk_user_token_expiry CHECK (purpose = 'legacy' OR expires_at IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 旧令牌继续有效（ADR §10）：逐行复制为 legacy，不设过期。
INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose, created_at)
SELECT id, 'legacy', '', token_hash, 'legacy', created_at
FROM app_user
WHERE token_hash IS NOT NULL;

-- ── 3. service_account（项目级服务账号）────────────────────────────────────
CREATE TABLE service_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    token_prefix VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_service_account_token_hash (token_hash),
    -- 名称在项目内忽略大小写唯一（函数索引）；与项目成员可同名（不共享成员表）。
    UNIQUE KEY uk_service_account_project_name_lower (project_id, (LOWER(name))),
    KEY idx_service_account_org (org_id),
    KEY idx_service_account_project (project_id),
    CONSTRAINT fk_service_account_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT fk_service_account_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT chk_service_account_role CHECK (role IN ('viewer', 'editor')),
    CONSTRAINT chk_service_account_status CHECK (status IN ('active', 'revoked'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 4. user_identity（OIDC 身份绑定）───────────────────────────────────────
CREATE TABLE user_identity (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    -- provider = 'oidc:<issuer>'（或 registration id）；subject = OIDC sub。
    -- issuer / sub 是大小写敏感的不透明标识，使用二进制排序规则。
    provider VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    email VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_user_identity_provider_subject (provider, subject),
    KEY idx_user_identity_user (user_id),
    KEY idx_user_identity_email (email),
    CONSTRAINT fk_user_identity_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 5. audit_event（管理动作审计）──────────────────────────────────────────
-- org_id / actor_user_id 不建外键：审计需在用户 / 组织删除后保留；actor_name 存快照。
CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    actor_name VARCHAR(128) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id VARCHAR(128) NULL,
    detail TEXT NULL,
    outcome VARCHAR(16) NOT NULL,
    ip VARCHAR(64) NULL,
    user_agent VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_audit_event_org_created (org_id, created_at),
    KEY idx_audit_event_org_action_created (org_id, action, created_at),
    KEY idx_audit_event_actor_created (actor_user_id, created_at),
    CONSTRAINT chk_audit_event_actor_type CHECK (actor_type IN ('user', 'service_account', 'anonymous')),
    CONSTRAINT chk_audit_event_outcome CHECK (outcome IN ('success', 'failure'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
