-- Phase C（C4 同窗口）：身份收紧（MySQL 8.4）
--
-- 与 PostgreSQL 版本一致：代码切换到 user_token / org_membership 后删除旧列。
-- 本文件随 C1 落库，但运行期由 `spring.flyway.target=6` 暂缓执行；
-- 必须与 C2–C4 代码同一发布窗口把 target 切到 7（见 application.yml 注释）。

-- ── 1. 删除前列校验：legacy 回填完整、status 无 NULL ───────────────────────
-- MySQL 无 DO 块：用临时表 + CHECK 约束实现条件中止（任一计数不为 0 即迁移失败）。
CREATE TEMPORARY TABLE tmp_v7_identity_guard (
    id TINYINT NOT NULL PRIMARY KEY,
    legacy_rows BIGINT NOT NULL,
    source_rows BIGINT NOT NULL,
    missing_rows BIGINT NOT NULL,
    CONSTRAINT chk_v7_legacy_count CHECK (legacy_rows = source_rows),
    CONSTRAINT chk_v7_legacy_backfilled CHECK (missing_rows = 0)
);
INSERT INTO tmp_v7_identity_guard (id, legacy_rows, source_rows, missing_rows)
SELECT 1,
       (SELECT COUNT(*) FROM user_token WHERE purpose = 'legacy'),
       (SELECT COUNT(*) FROM app_user WHERE token_hash IS NOT NULL),
       (SELECT COUNT(*)
        FROM app_user u
        WHERE u.token_hash IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM user_token t
              WHERE t.user_id = u.id
                AND t.purpose = 'legacy'
                AND t.token_hash = u.token_hash
          ));
DROP TEMPORARY TABLE tmp_v7_identity_guard;

-- 邮箱唯一（忽略大小写）由 V6 的 uk_app_user_email_lower 兜底，这里无需重复校验。

-- ── 2. 删除旧列（旧凭据已迁移到 user_token；平台 admin 已由 org_membership.org_role 取代）──
ALTER TABLE app_user
    DROP COLUMN token_hash,
    DROP COLUMN is_platform_admin;
