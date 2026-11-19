-- Phase C（C4 同窗口）：身份收紧（PostgreSQL）
--
-- 前提：C2–C4 代码已切换到 user_token / org_membership，不再读写 app_user.token_hash 与
-- app_user.is_platform_admin。旧 token 在 V6 已回填 user_token(purpose=legacy)，删列后仍可认证。
--
-- 注意：本文件随 C1 落库，但运行期由 `spring.flyway.target=6` 暂缓执行；
-- 必须与 C2–C4 代码同一发布窗口把 target 切到 7（见 application.yml 注释）。

-- ── 1. 删除前列校验：legacy 回填完整、status 无 NULL ───────────────────────
DO $$
DECLARE
    legacy_rows BIGINT;
    source_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO legacy_rows FROM user_token WHERE purpose = 'legacy';
    SELECT COUNT(*) INTO source_rows FROM app_user WHERE token_hash IS NOT NULL;

    IF legacy_rows <> source_rows THEN
        RAISE EXCEPTION
            'V7 中止：user_token(legacy) 行数 % 与 app_user.token_hash 非空行数 % 不一致，需先重新回填',
            legacy_rows, source_rows;
    END IF;

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

-- ── 2. 删除旧列（旧凭据已迁移到 user_token；平台 admin 已由 org_membership.org_role 取代）──
ALTER TABLE app_user DROP COLUMN token_hash;
ALTER TABLE app_user DROP COLUMN is_platform_admin;
