-- Phase A：多项目骨架（单组织，MySQL 8.4）
-- 过渡策略同 PostgreSQL：新列先允许 NULL，由 A2 写入路径补齐。
-- 阶段 A 不引入 email / password_hash / mfa_enabled（阶段 C）。

-- ── 1. 组织 ────────────────────────────────────────────────────────────────
CREATE TABLE org (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    plan VARCHAR(32) NULL,
    trial_ends_at DATETIME(6) NULL,
    quota_json TEXT NULL,
    suspended_reason TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_org_slug (slug),
    CONSTRAINT chk_org_status CHECK (status IN ('pending_verification', 'active', 'suspended', 'archived', 'deleted'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO org (name, slug, status) VALUES ('default', 'default', 'active');

-- ── 2. 组织成员关系（app_user 保持全局身份）────────────────────────────────
CREATE TABLE org_membership (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    org_role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_org_membership_user_org (user_id, org_id),
    KEY idx_org_membership_org (org_id),
    CONSTRAINT fk_org_membership_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_org_membership_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT chk_org_membership_role CHECK (org_role IN ('org_admin', 'org_member')),
    CONSTRAINT chk_org_membership_status CHECK (status IN ('active', 'disabled', 'invited'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO org_membership (user_id, org_id, org_role, status)
SELECT u.id, (SELECT id FROM org WHERE slug = 'default' LIMIT 1),
       CASE WHEN u.is_platform_admin = 1 THEN 'org_admin' ELSE 'org_member' END,
       'active'
FROM app_user u;

-- ── 3. Git 连接（阶段 A 只建表；外部 provider 连接器留 D1）─────────────────
CREATE TABLE git_connection (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    external_account_id VARCHAR(128) NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    credentials_enc TEXT NULL,
    webhook_secret_enc TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'active',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_git_connection_org (org_id),
    CONSTRAINT fk_git_connection_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT chk_git_connection_status CHECK (status IN ('active', 'disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 4. 项目：org / slug / 可见性 / owner / 归档 ────────────────────────────
ALTER TABLE project
    ADD COLUMN org_id BIGINT NULL,
    ADD COLUMN slug VARCHAR(64) NULL,
    ADD COLUMN description VARCHAR(1024) NOT NULL DEFAULT '',
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    ADD COLUMN owner_id BIGINT NULL,
    ADD COLUMN archived TINYINT(1) NOT NULL DEFAULT 0;

UPDATE project
SET org_id = (SELECT id FROM org WHERE slug = 'default' LIMIT 1),
    slug = CASE WHEN name = 'default' THEN 'default' ELSE CONCAT('project-', id) END,
    owner_id = COALESCE(owner_id, (SELECT id FROM app_user WHERE is_platform_admin = 1 ORDER BY id LIMIT 1))
WHERE org_id IS NULL OR slug IS NULL;

ALTER TABLE project DROP INDEX uk_project_name;
ALTER TABLE project ADD UNIQUE KEY uk_project_org_slug (org_id, slug);
ALTER TABLE project ADD CONSTRAINT fk_project_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE project ADD CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE RESTRICT;
ALTER TABLE project ADD CONSTRAINT chk_project_visibility CHECK (visibility IN ('private', 'internal', 'public'));
CREATE INDEX idx_project_org ON project (org_id);

-- ── 5. 仓库：org / connection / external id ────────────────────────────────
ALTER TABLE repo
    ADD COLUMN org_id BIGINT NULL,
    ADD COLUMN connection_id BIGINT NULL,
    ADD COLUMN external_repo_id VARCHAR(128) NULL;

UPDATE repo r
JOIN project p ON r.project_id = p.id
SET r.org_id = p.org_id,
    r.external_repo_id = MD5(r.url)
WHERE r.org_id IS NULL OR r.external_repo_id IS NULL;

ALTER TABLE repo ADD CONSTRAINT fk_repo_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE repo ADD CONSTRAINT fk_repo_connection FOREIGN KEY (connection_id) REFERENCES git_connection(id) ON DELETE RESTRICT;
CREATE INDEX idx_repo_org ON repo (org_id);

-- ── 6. 真源绑定：单主约束 + 现有仓库回填一条 active ────────────────────────
CREATE TABLE repo_binding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    repo_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    connection_id BIGINT NULL,
    external_repo_id VARCHAR(128) NULL,
    remote_url VARCHAR(512) NOT NULL,
    branch VARCHAR(128) NOT NULL DEFAULT 'main',
    status VARCHAR(32) NOT NULL DEFAULT 'candidate',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    retired_at DATETIME(6) NULL,
    -- MySQL 无部分索引：用生成列 + 唯一键实现「同一 repo 至多一条 active」
    active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY uk_repo_binding_active (repo_id, active_flag),
    KEY idx_repo_binding_repo (repo_id),
    CONSTRAINT fk_repo_binding_repo FOREIGN KEY (repo_id) REFERENCES repo(id) ON DELETE RESTRICT,
    CONSTRAINT fk_repo_binding_connection FOREIGN KEY (connection_id) REFERENCES git_connection(id) ON DELETE RESTRICT,
    CONSTRAINT chk_repo_binding_kind CHECK (kind IN ('managed', 'external')),
    CONSTRAINT chk_repo_binding_status CHECK (status IN ('candidate', 'active', 'retired'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO repo_binding (repo_id, kind, connection_id, external_repo_id, remote_url, branch, status, created_at)
SELECT r.id, 'managed', NULL, r.external_repo_id, r.url, r.branch, 'active', r.created_at
FROM repo r;

-- ── 7. membership 补 project 外键（project 已在管理库）────────────────────
ALTER TABLE membership ADD CONSTRAINT fk_membership_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT;

-- ── 8. 租户冗余列 ──────────────────────────────────────────────────────────
ALTER TABLE comment ADD COLUMN org_id BIGINT NULL;
ALTER TABLE subscription ADD COLUMN org_id BIGINT NULL;
ALTER TABLE notification ADD COLUMN org_id BIGINT NULL;
ALTER TABLE review ADD COLUMN org_id BIGINT NULL;
ALTER TABLE mock_rule ADD COLUMN org_id BIGINT NULL;
ALTER TABLE changelog ADD COLUMN org_id BIGINT NULL;
ALTER TABLE release_marker ADD COLUMN org_id BIGINT NULL;

UPDATE comment c JOIN repo r ON c.repo_id = r.id SET c.org_id = r.org_id WHERE c.org_id IS NULL;
UPDATE subscription s JOIN repo r ON s.repo_id = r.id SET s.org_id = r.org_id WHERE s.org_id IS NULL;
UPDATE notification n JOIN repo r ON n.repo_id = r.id SET n.org_id = r.org_id WHERE n.org_id IS NULL;
UPDATE review v JOIN repo r ON v.repo_id = r.id SET v.org_id = r.org_id WHERE v.org_id IS NULL;
UPDATE mock_rule m JOIN repo r ON m.repo_id = r.id SET m.org_id = r.org_id WHERE m.org_id IS NULL;
UPDATE release_marker rm JOIN repo r ON rm.repo_id = r.id SET rm.org_id = r.org_id WHERE rm.org_id IS NULL;
UPDATE changelog SET org_id = (SELECT id FROM org WHERE slug = 'default' LIMIT 1) WHERE org_id IS NULL;

ALTER TABLE comment ADD CONSTRAINT fk_comment_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE subscription ADD CONSTRAINT fk_subscription_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE notification ADD CONSTRAINT fk_notification_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE review ADD CONSTRAINT fk_review_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE mock_rule ADD CONSTRAINT fk_mock_rule_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE changelog ADD CONSTRAINT fk_changelog_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;
ALTER TABLE release_marker ADD CONSTRAINT fk_release_marker_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT;

CREATE INDEX idx_comment_org ON comment (org_id, created_at);
CREATE INDEX idx_subscription_org ON subscription (org_id);
CREATE INDEX idx_notification_org ON notification (org_id, created_at);
CREATE INDEX idx_review_org_status ON review (org_id, status);
CREATE INDEX idx_mock_rule_org ON mock_rule (org_id);
CREATE INDEX idx_changelog_org ON changelog (org_id, created_at);
CREATE INDEX idx_release_marker_org ON release_marker (org_id);
