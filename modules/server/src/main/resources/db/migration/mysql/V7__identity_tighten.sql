-- Phase C（C4 同窗口）：身份收紧（MySQL 8.4）
--
-- 与 PostgreSQL 版本一致：删除旧列前把仍缺回填的 app_user.token_hash 补记为 revoked legacy，
-- 旧值已失效但保留审计痕迹；补记后仍有缺失才中止。代码与迁移同一发布窗口。

-- ── 1. 补记缺失的旧 token（revoked）──────────────────────────────────────
INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose, created_at, revoked_at)
SELECT u.id, 'legacy-rotated', '', u.token_hash, 'legacy', u.created_at, CURRENT_TIMESTAMP(6)
FROM app_user u
WHERE u.token_hash IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM user_token t
      WHERE t.user_id = u.id
        AND t.purpose = 'legacy'
        AND t.token_hash = u.token_hash
  );

-- ── 2. 删除前列校验：每个 app_user.token_hash 均已落 user_token、status 无 NULL ──
CREATE TEMPORARY TABLE tmp_v7_identity_guard (
    id TINYINT NOT NULL PRIMARY KEY,
    missing_rows BIGINT NOT NULL,
    null_status_rows BIGINT NOT NULL,
    CONSTRAINT chk_v7_legacy_backfilled CHECK (missing_rows = 0),
    CONSTRAINT chk_v7_status_not_null CHECK (null_status_rows = 0)
);
INSERT INTO tmp_v7_identity_guard (id, missing_rows, null_status_rows)
SELECT 1,
       (SELECT COUNT(*)
        FROM app_user u
        WHERE u.token_hash IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM user_token t
              WHERE t.user_id = u.id
                AND t.purpose = 'legacy'
                AND t.token_hash = u.token_hash
          )),
       (SELECT COUNT(*) FROM app_user WHERE status IS NULL);
DROP TEMPORARY TABLE tmp_v7_identity_guard;

-- 邮箱唯一（忽略大小写）由 V6 的 uk_app_user_email_lower 兜底，这里无需重复校验。

-- ── 3. 删除旧列（旧凭据已迁移到 user_token；平台 admin 已由 org_membership.org_role 取代）──
ALTER TABLE app_user
    DROP COLUMN token_hash,
    DROP COLUMN is_platform_admin;
