-- Phase B（B1）：团队与跨团队订阅（MySQL 8.4）
--
-- 与 PostgreSQL 版本保持一致：同 org 内按 lower(trim(team)) 合并；
-- V4 只建表 + 回填，team_id 暂允许 NULL，旧唯一键与旧 team 列保留（B1 可独立发布）；
-- B2 写入路径上线后由 V5 收紧（回填 NULL、去重、切换唯一键、NOT NULL）。

-- ── 1. 团队 ────────────────────────────────────────────────────────────────
CREATE TABLE team (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(1024) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_team_org_name (org_id, name),
    KEY idx_team_org (org_id),
    CONSTRAINT fk_team_org FOREIGN KEY (org_id) REFERENCES org(id) ON DELETE RESTRICT,
    CONSTRAINT chk_team_name CHECK (TRIM(name) <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 2. 团队成员（lead / member）─────────────────────────────────────────────
CREATE TABLE team_member (
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'member',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id, user_id),
    KEY idx_team_member_user (user_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT chk_team_member_role CHECK (role IN ('lead', 'member'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── 3. 从旧订阅文本建团队：同 org 内按 lower(trim(name)) 合并 ───────────────
INSERT INTO team (org_id, name, description, created_at)
SELECT COALESCE(s.org_id, r.org_id) AS org_id,
       MIN(TRIM(s.team)) AS name,
       '',
       MIN(s.created_at)
FROM subscription s
LEFT JOIN repo r ON r.id = s.repo_id
WHERE COALESCE(s.org_id, r.org_id) IS NOT NULL
GROUP BY COALESCE(s.org_id, r.org_id), LOWER(TRIM(s.team));

-- ── 4. 回填 subscription.team_id ───────────────────────────────────────────
ALTER TABLE subscription ADD COLUMN team_id BIGINT NULL;

UPDATE subscription s
JOIN repo r ON r.id = s.repo_id
JOIN team t
  ON t.org_id = COALESCE(s.org_id, r.org_id)
 AND LOWER(t.name) = LOWER(TRIM(s.team))
SET s.team_id = t.id;

-- ── 5. 回填 notification.team_id（匹配不到保留 NULL）───────────────────────
ALTER TABLE notification ADD COLUMN team_id BIGINT NULL;

UPDATE notification n
JOIN repo r ON r.id = n.repo_id
JOIN team t
  ON t.org_id = COALESCE(n.org_id, r.org_id)
 AND LOWER(t.name) = LOWER(TRIM(n.team))
SET n.team_id = t.id;

-- ── 6. 旧数据成员回填：团队名 = 用户名（忽略大小写）且为同组织 active 成员 → lead
INSERT INTO team_member (team_id, user_id, role, created_at)
SELECT t.id, u.id, 'lead', CURRENT_TIMESTAMP(6)
FROM team t
JOIN app_user u ON LOWER(u.username) = LOWER(t.name)
JOIN org_membership om
  ON om.user_id = u.id
 AND om.org_id = t.org_id
 AND om.status = 'active';

-- ── 7. 过渡期约束（B2 写入路径上线后由 V5 收紧）───────────────────────────
-- 保留旧唯一键 uk_subscription_endpoint 与可空 team_id，B1 可独立发布。
ALTER TABLE subscription
    ADD KEY idx_subscription_org_team (org_id, team_id),
    ADD CONSTRAINT fk_subscription_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE RESTRICT;

ALTER TABLE notification
    ADD KEY idx_notification_org_team (org_id, team_id, created_at),
    ADD CONSTRAINT fk_notification_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE SET NULL;
