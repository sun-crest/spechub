-- Phase C（C4 同窗口）：身份收紧（PostgreSQL）
--
-- 前提：C2–C4 代码已切换到 user_token / org_membership，不再读写 app_user.token_hash 与
-- app_user.is_platform_admin。旧 token 在 V6 已回填 user_token(purpose=legacy)，删列后仍可认证。
--
-- C4 调整：C2/C3 的 admin-token 轮换会停止使用旧 legacy 行；V7 删除前列前把仍缺回填的
-- app_user.token_hash 补记为 **revoked** legacy 行（旧值已失效，保留审计痕迹），
-- 不再因行数不一致中止迁移。真正缺失回填（补记后仍缺）才中止。
--
-- 注意：本迁移与 C2–C4 代码同一发布窗口；`spring.flyway.target=7` 由 C4 切到。

-- ── 1. 补记缺失的旧 token（revoked）──────────────────────────────────────
INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose, created_at, revoked_at)
SELECT u.id, 'legacy-rotated', '', u.token_hash, 'legacy', u.created_at, now()
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
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app_user u
        WHERE u.token_hash IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM user_token t
              WHERE t.user_id = u.id
                AND t.purpose = 'legacy'
                AND t.token_hash = u.token_hash
          )
    ) THEN
        RAISE EXCEPTION 'V7 中止：仍有 app_user.token_hash 未回填到 user_token';
    END IF;

    IF EXISTS (SELECT 1 FROM app_user WHERE status IS NULL) THEN
        RAISE EXCEPTION 'V7 中止：app_user.status 存在 NULL';
    END IF;
END $$;

-- 邮箱唯一（忽略大小写）由 V6 的 uk_app_user_email_lower 兜底，这里无需重复校验。

-- ── 3. 删除旧列（旧凭据已迁移到 user_token；平台 admin 已由 org_membership.org_role 取代）──
ALTER TABLE app_user DROP COLUMN token_hash;
ALTER TABLE app_user DROP COLUMN is_platform_admin;
