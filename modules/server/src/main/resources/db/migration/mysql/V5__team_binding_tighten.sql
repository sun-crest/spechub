-- Phase B（B2）：团队绑定收紧（MySQL 8.4）
--
-- 与 PostgreSQL 版本一致：team_member 代理主键、过渡期 NULL 回填、重复订阅去重、
-- 唯一键切换、team_id NOT NULL、旧 team 列改可空。

-- ── 1. team_member 代理主键 ────────────────────────────────────────────────
-- 先补 team_id 索引，保证删除复合主键时外键仍有可用索引。
ALTER TABLE team_member ADD KEY idx_team_member_team (team_id);
ALTER TABLE team_member DROP PRIMARY KEY;
ALTER TABLE team_member ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY FIRST;
ALTER TABLE team_member ADD UNIQUE KEY uk_team_member_team_user (team_id, user_id);
ALTER TABLE team_member DROP KEY idx_team_member_team;

-- ── 2. 回填过渡期新增的 NULL team_id ─────────────────────────────────────
UPDATE subscription s
JOIN repo r ON r.id = s.repo_id
JOIN team t
  ON t.org_id = COALESCE(s.org_id, r.org_id)
 AND LOWER(t.name) = LOWER(TRIM(s.team))
SET s.team_id = t.id
WHERE s.team_id IS NULL;

INSERT INTO team (org_id, name, description, created_at)
SELECT x.org_id, x.name, '', x.created_at
FROM (
    SELECT COALESCE(s.org_id, r.org_id) AS org_id,
           MIN(TRIM(s.team)) AS name,
           MIN(s.created_at) AS created_at
    FROM subscription s
    LEFT JOIN repo r ON r.id = s.repo_id
    WHERE s.team_id IS NULL
      AND COALESCE(s.org_id, r.org_id) IS NOT NULL
    GROUP BY COALESCE(s.org_id, r.org_id), LOWER(TRIM(s.team))
) x
LEFT JOIN team t ON t.org_id = x.org_id AND LOWER(t.name) = LOWER(x.name)
WHERE t.id IS NULL;

UPDATE subscription s
JOIN repo r ON r.id = s.repo_id
JOIN team t
  ON t.org_id = COALESCE(s.org_id, r.org_id)
 AND LOWER(t.name) = LOWER(TRIM(s.team))
SET s.team_id = t.id
WHERE s.team_id IS NULL;

-- B1→B2 窗口内旧代码写入的通知同样按团队名回填（匹配不到保留 NULL）。
UPDATE notification n
JOIN repo r ON r.id = n.repo_id
JOIN team t
  ON t.org_id = COALESCE(n.org_id, r.org_id)
 AND LOWER(t.name) = LOWER(TRIM(n.team))
SET n.team_id = t.id
WHERE n.team_id IS NULL;

-- ── 3. 同名合并后的重复订阅去重（保留 id 最大的一条）──────────────────────
DELETE s
FROM subscription s
JOIN subscription d
  ON s.repo_id = d.repo_id
 AND s.method = d.method
 AND s.path = d.path
 AND s.team_id = d.team_id
 AND s.id < d.id;

-- ── 4. 约束收紧 ────────────────────────────────────────────────────────────
-- 仍有 NULL 时 MODIFY NOT NULL 失败，需先修复数据。
ALTER TABLE subscription MODIFY COLUMN team_id BIGINT NOT NULL;
ALTER TABLE subscription DROP INDEX uk_subscription_endpoint;
ALTER TABLE subscription ADD UNIQUE KEY uk_subscription_team_endpoint (repo_id, method, path, team_id);
ALTER TABLE subscription MODIFY COLUMN team VARCHAR(128) NULL;
