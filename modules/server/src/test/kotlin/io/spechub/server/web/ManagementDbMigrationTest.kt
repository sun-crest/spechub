package io.spechub.server.web

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A9/B1/B2/C1 迁移矩阵（PostgreSQL / MySQL 由 `-Dspechub.test.db` 选择）：
 * - V2 有数据 → V6：org / project / repo / repo_binding / 租户列 / 团队订阅回填 + 身份列与 legacy token；
 * - V3 有数据 → V4：过渡期同名团队合并、跨 org 同名隔离、成员启发式回填、旧唯一键保留；
 * - V4 有数据 → V5：过渡期 NULL 回填、重复订阅去重、唯一键切换与 NOT NULL 收紧；
 * - V5 有数据 → V6：status 回填、旧 token 迁移 user_token(legacy)、新表约束、不建会话表；
 * - V6 有数据 → V7：旧列删除、legacy token 仍可按 hash 定位；未回填 token 补记为 revoked legacy；
 * - 守卫：重复 token_hash 中止 V6；
 * - 空库 V1 → V7：七版一次性迁移成功，bootstrap 默认组织就绪。
 *
 * 不走 Spring：直接 Testcontainers 目标库 + Flyway 编程式迁移，避免 @SpringBootTest 的 Flyway 自动装配。
 * 运行期 `spring.flyway.target=6` 暂缓 V7（C2–C4 代码同窗口切换），本测试显式指定 target 验证 V6 / V7。
 */
class ManagementDbMigrationTest {
    private fun newTarget(tag: String): TestManagementDb.DbTarget {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(8)
        return TestManagementDb.target("t_mig_${tag}_$suffix")
    }

    private fun flyway(
        db: TestManagementDb.DbTarget,
        version: String?,
    ): Flyway {
        var cfg =
            Flyway
                .configure()
                .dataSource(db.jdbcUrl, db.username, db.password)
                .locations("classpath:db/migration/${db.vendor}")
        if (db.vendor == "postgresql") {
            val schema = checkNotNull(db.schema) { "PostgreSQL 目标必须带 schema" }
            cfg = cfg.schemas(schema).defaultSchema(schema)
        }
        if (version != null) cfg = cfg.target(MigrationVersion.fromVersion(version))
        return cfg.load()
    }

    // ── V2 → V4 ────────────────────────────────────────────────────────────

    private data class Seed(
        val adminId: Long,
        val userId: Long,
        val projectDefaultId: Long,
        val projectLegacyId: Long,
        val repoAId: Long,
        val repoBId: Long,
    )

    private fun seedV2(db: TestManagementDb.DbTarget): Seed =
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            val admin =
                insert(
                    c,
                    "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                    "mig-admin",
                    "Mig Admin",
                    "hash-admin",
                    true,
                )
            val user =
                insert(
                    c,
                    "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                    "mig-user",
                    "Mig User",
                    "hash-user",
                    false,
                )
            val pDefault = insert(c, "INSERT INTO project (name) VALUES (?)", "default")
            val pLegacy = insert(c, "INSERT INTO project (name) VALUES (?)", "legacy")
            val repoA =
                insert(
                    c,
                    "INSERT INTO repo (project_id, url, branch, sync_status) VALUES (?, ?, ?, ?)",
                    pDefault,
                    "file:///legacy/a.git",
                    "develop",
                    "ok",
                )
            val repoB =
                insert(
                    c,
                    "INSERT INTO repo (project_id, url, branch, sync_status) VALUES (?, ?, ?, ?)",
                    pLegacy,
                    "file:///legacy/b.git",
                    "main",
                    "error",
                )
            insert(
                c,
                "INSERT INTO membership (user_id, project_id, role) VALUES (?, ?, ?)",
                user,
                pDefault,
                "viewer",
            )
            insert(c, "INSERT INTO comment (repo_id, method, path, author, body) VALUES (?, ?, ?, ?, ?)", repoA, "GET", "/pets", "mig-user", "旧评论")
            insert(
                c,
                "INSERT INTO subscription (repo_id, method, path, team, notify_url) VALUES (?, ?, ?, ?, ?)",
                repoA,
                "GET",
                "/pets",
                "core",
                "",
            )
            insert(c, "INSERT INTO notification (team, repo_id, method, path, summary) VALUES (?, ?, ?, ?, ?)", "core", repoA, "GET", "/pets", "旧通知")
            insert(
                c,
                "INSERT INTO review (repo_id, path, new_content, diff, requester) VALUES (?, ?, ?, ?, ?)",
                repoA,
                "specs/pet.yaml",
                "openapi: 3.1.0",
                "",
                "mig-user",
            )
            insert(
                c,
                "INSERT INTO mock_rule (repo_id, field_pattern, rule_type, rule_value) VALUES (?, ?, ?, ?)",
                repoA,
                "id",
                "sequence",
                "",
            )
            insert(c, "INSERT INTO release_marker (repo_id, path, spec_hash) VALUES (?, ?, ?)", repoA, "specs/pet.yaml", "hash-1")
            insert(
                c,
                "INSERT INTO changelog (repo_id, api_id, actor, action, detail) VALUES (?, ?, ?, ?, ?)",
                repoA,
                null,
                "mig-user",
                "spec.write",
                "旧仓变更",
            )
            // org 级动作（repo_id 为空）：V3 应回填默认组织
            insert(
                c,
                "INSERT INTO changelog (repo_id, api_id, actor, action, detail) VALUES (NULL, NULL, ?, ?, ?)",
                "mig-user",
                "project.create",
                "旧项目动作",
            )
            Seed(admin, user, pDefault, pLegacy, repoA, repoB)
        }

    @Test
    fun `V2 有数据升级到 V6 - 组织项目仓库绑定与身份回填`() {
        val db = newTarget("v2")
        flyway(db, "2").migrate()
        val seed = seedV2(db)

        val result = flyway(db, "6").migrate()
        assertTrue(result.migrationsExecuted >= 4, "V3–V6 应被执行：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            val orgId = one(c, "SELECT id FROM org WHERE slug = 'default'")["id"] as Long

            // 项目：org / slug / 默认可见性 / owner / 未归档
            val projectDefault =
                one(
                    c,
                    "SELECT org_id, slug, visibility, owner_id, CASE WHEN archived THEN 1 ELSE 0 END AS archived FROM project WHERE id = ?",
                    seed.projectDefaultId,
                )
            assertEquals(orgId, projectDefault["org_id"], "默认项目回填 org")
            assertEquals("default", projectDefault["slug"], "default 项目 slug 保持 default")
            assertEquals("private", projectDefault["visibility"], "可见性默认 private")
            assertEquals(seed.adminId, projectDefault["owner_id"], "默认项目 owner = 平台 admin")
            assertEquals(0, (projectDefault["archived"] as Number).toInt())

            val legacySlug = one(c, "SELECT slug FROM project WHERE id = ?", seed.projectLegacyId)["slug"]
            assertEquals("project-${seed.projectLegacyId}", legacySlug, "非 default 项目补 project-<id> slug")

            // 仓库：org + 外部 ID；每个 repo 恰好一条 active managed binding
            for (repoId in listOf(seed.repoAId, seed.repoBId)) {
                val repo = one(c, "SELECT org_id, external_repo_id, url, branch FROM repo WHERE id = ?", repoId)
                assertEquals(orgId, repo["org_id"], "repo $repoId 回填 org")
                assertEquals(md5(repo["url"] as String), repo["external_repo_id"], "external_repo_id = md5(url)")
                val binding = one(c, "SELECT kind, status, remote_url, branch FROM repo_binding WHERE repo_id = ?", repoId)
                assertEquals("managed", binding["kind"])
                assertEquals("active", binding["status"])
                assertEquals(repo["url"], binding["remote_url"], "binding 采用 repo.url")
                assertEquals(repo["branch"], binding["branch"], "binding 采用 repo.branch")
                assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM repo_binding WHERE repo_id = ? AND status = 'active'", repoId))
            }

            // 组织成员关系：平台 admin → org_admin，普通用户 → org_member（C1 再断言 admin 关系）
            assertEquals(2, count(c, "SELECT COUNT(*) AS c FROM org_membership"))
            assertEquals("org_admin", one(c, "SELECT org_role FROM org_membership WHERE user_id = ?", seed.adminId)["org_role"])
            assertEquals("org_member", one(c, "SELECT org_role FROM org_membership WHERE user_id = ?", seed.userId)["org_role"])

            // V6：现有用户全部 active，旧 token 逐行回填为 user_token(purpose=legacy)
            assertEquals(2, count(c, "SELECT COUNT(*) AS c FROM app_user WHERE status = 'active'"))
            assertEquals(2, count(c, "SELECT COUNT(*) AS c FROM user_token WHERE purpose = 'legacy'"))
            assertEquals(
                "hash-admin",
                one(c, "SELECT token_hash FROM user_token WHERE user_id = ? AND purpose = 'legacy'", seed.adminId)["token_hash"],
            )
            assertEquals(
                "hash-user",
                one(c, "SELECT token_hash FROM user_token WHERE user_id = ? AND purpose = 'legacy'", seed.userId)["token_hash"],
            )
            assertNull(one(c, "SELECT expires_at FROM user_token WHERE user_id = ?", seed.adminId)["expires_at"], "legacy 令牌不过期")

            // 租户冗余列回填
            for (table in listOf("comment", "subscription", "notification", "review", "mock_rule", "release_marker")) {
                assertEquals(orgId, one(c, "SELECT org_id FROM $table WHERE repo_id = ?", seed.repoAId)["org_id"], "$table.org_id 回填")
            }
            assertEquals(orgId, one(c, "SELECT org_id FROM changelog WHERE repo_id = ?", seed.repoAId)["org_id"])
            assertEquals(
                orgId,
                one(c, "SELECT org_id FROM changelog WHERE repo_id IS NULL")["org_id"],
                "org 级 changelog 回填默认组织",
            )

            // 单主约束：第二条 active 必须被数据库拒绝
            assertFailsWith<SQLException> {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO repo_binding (repo_id, kind, remote_url, branch, status) " +
                            "VALUES (${seed.repoAId}, 'managed', 'file:///dup.git', 'main', 'active')",
                    )
                }
            }

            // B1：旧 team 文本建团队，订阅/通知回填 team_id；旧列保留为快照
            val team = one(c, "SELECT id, name FROM team WHERE org_id = ?", orgId)
            assertEquals("core", (team["name"] as String).lowercase(), "旧 team 文本建团队")
            assertEquals(
                team["id"],
                one(c, "SELECT team_id FROM subscription WHERE repo_id = ?", seed.repoAId)["team_id"],
                "subscription.team_id 回填",
            )
            assertEquals(
                team["id"],
                one(c, "SELECT team_id FROM notification WHERE repo_id = ?", seed.repoAId)["team_id"],
                "notification.team_id 回填",
            )
            assertEquals("core", one(c, "SELECT team FROM subscription WHERE repo_id = ?", seed.repoAId)["team"], "旧 team 文本保留")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team_member"), "用户名与团队名不同，不回填成员")

            // V5 后按 (repo_id, method, path, team_id) 唯一：同 endpoint + 同团队重复订阅被拒绝
            assertFailsWith<SQLException> {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) " +
                            "VALUES (${seed.repoAId}, $orgId, 'GET', '/pets', 'core', ${team["id"]}, '')",
                    )
                }
            }
        }
    }

    // ── V3 → V4 ────────────────────────────────────────────────────────────

    @Test
    fun `V3 有数据升级到 V4 - 同名合并跨组织隔离与成员回填`() {
        val db = newTarget("v3")
        flyway(db, "3").migrate()

        val seed =
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                val defaultOrgId = one(c, "SELECT id FROM org WHERE slug = 'default'")["id"] as Long
                val acmeOrgId = insert(c, "INSERT INTO org (name, slug, status) VALUES ('acme', 'acme', 'active')")
                val coreUserId =
                    insert(
                        c,
                        "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                        "core",
                        "Core User",
                        "hash-core",
                        false,
                    )
                insert(c, "INSERT INTO org_membership (user_id, org_id, org_role, status) VALUES (?, ?, 'org_member', 'active')", coreUserId, acmeOrgId)

                val defaultProject =
                    insert(
                        c,
                        "INSERT INTO project (name, org_id, slug, visibility, archived) VALUES (?, ?, ?, ?, ?)",
                        "legacy-default",
                        defaultOrgId,
                        "legacy-default",
                        "private",
                        false,
                    )
                val acmeProject =
                    insert(
                        c,
                        "INSERT INTO project (name, org_id, slug, visibility, archived) VALUES (?, ?, ?, ?, ?)",
                        "legacy-acme",
                        acmeOrgId,
                        "legacy-acme",
                        "private",
                        false,
                    )
                val defaultRepo =
                    insert(
                        c,
                        "INSERT INTO repo (project_id, url, branch, sync_status, org_id, external_repo_id) VALUES (?, ?, ?, ?, ?, ?)",
                        defaultProject,
                        "file:///legacy/default.git",
                        "main",
                        "ok",
                        defaultOrgId,
                        "ext-default",
                    )
                val acmeRepo =
                    insert(
                        c,
                        "INSERT INTO repo (project_id, url, branch, sync_status, org_id, external_repo_id) VALUES (?, ?, ?, ?, ?, ?)",
                        acmeProject,
                        "file:///legacy/acme.git",
                        "main",
                        "ok",
                        acmeOrgId,
                        "ext-acme",
                    )

                // 同 org 内 'core' / ' Core ' 合并；跨 org 同名各自建团队
                val subA = insert(c, "INSERT INTO subscription (repo_id, org_id, method, path, team, notify_url) VALUES (?, NULL, 'GET', '/pets', 'core', '')", defaultRepo)
                val subB = insert(c, "INSERT INTO subscription (repo_id, org_id, method, path, team, notify_url) VALUES (?, ?, 'GET', '/pets', ' Core ', '')", defaultRepo, defaultOrgId)
                val subC = insert(c, "INSERT INTO subscription (repo_id, org_id, method, path, team, notify_url) VALUES (?, ?, 'GET', '/pets', 'core', '')", acmeRepo, acmeOrgId)
                // 可匹配的历史通知 + 无法匹配的 ghost 团队
                val notifA = insert(c, "INSERT INTO notification (team, repo_id, org_id, method, path, summary) VALUES ('core', ?, ?, 'GET', '/pets', '旧通知')", defaultRepo, defaultOrgId)
                val notifGhost = insert(c, "INSERT INTO notification (team, repo_id, org_id, method, path, summary) VALUES ('ghost', ?, ?, 'GET', '/pets', '幽灵通知')", defaultRepo, defaultOrgId)

                SeedV3(defaultOrgId, acmeOrgId, defaultRepo, coreUserId, subA, subB, subC, notifA, notifGhost)
            }

        val result = flyway(db, "4").migrate()
        assertTrue(result.migrationsExecuted >= 1, "V4 应被执行：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            // 同 org 同名合并为一行；跨 org 同名各自一行
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM team WHERE org_id = ?", seed.defaultOrgId), "同 org 同名合并")
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM team WHERE org_id = ?", seed.acmeOrgId), "跨 org 同名隔离")
            val defaultTeam = one(c, "SELECT id, name FROM team WHERE org_id = ?", seed.defaultOrgId)
            val acmeTeam = one(c, "SELECT id, name FROM team WHERE org_id = ?", seed.acmeOrgId)
            assertEquals("core", (defaultTeam["name"] as String).lowercase(), "合并后团队名")
            assertEquals("core", (acmeTeam["name"] as String).lowercase(), "跨 org 团队名")
            assertTrue(defaultTeam["id"] != acmeTeam["id"], "不同 org 的团队 id 不同")

            // 过渡期两条同 org 订阅都保留（V5 再按 team_id 去重）；均指向合并后的 default team
            assertEquals(defaultTeam["id"], one(c, "SELECT team_id FROM subscription WHERE id = ?", seed.subA)["team_id"])
            assertEquals(defaultTeam["id"], one(c, "SELECT team_id FROM subscription WHERE id = ?", seed.subB)["team_id"])
            assertEquals(acmeTeam["id"], one(c, "SELECT team_id FROM subscription WHERE id = ?", seed.subC)["team_id"])
            assertEquals(3, count(c, "SELECT COUNT(*) AS c FROM subscription"), "过渡期保留重复订阅")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM subscription WHERE team_id IS NULL"), "订阅 team_id 全部回填")

            // 通知：可匹配的填 team_id，ghost 保留 NULL 且旧文本快照不丢
            assertEquals(defaultTeam["id"], one(c, "SELECT team_id FROM notification WHERE id = ?", seed.notifA)["team_id"])
            assertEquals(null, one(c, "SELECT team_id FROM notification WHERE id = ?", seed.notifGhost)["team_id"], "无法匹配的历史通知保留 NULL")
            assertEquals("ghost", one(c, "SELECT team FROM notification WHERE id = ?", seed.notifGhost)["team"])

            // 成员回填：用户名 'core' 是 acme 组织成员 → acme 团队 lead；default 团队无成员
            val acmeLead = one(c, "SELECT user_id, role FROM team_member WHERE team_id = ?", acmeTeam["id"])
            assertEquals(seed.coreUserId, acmeLead["user_id"], "团队名 = 用户名 → lead")
            assertEquals("lead", acmeLead["role"])
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team_member WHERE team_id = ?", defaultTeam["id"]), "不同组织不参与回填")

            // 过渡期旧唯一键仍生效；有订阅的团队不能删除（FK RESTRICT）
            assertFailsWith<SQLException> {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) " +
                            "VALUES (${seed.defaultRepo}, ${seed.defaultOrgId}, 'GET', '/pets', 'core', ${defaultTeam["id"]}, '')",
                    )
                }
            }
            assertFailsWith<SQLException> {
                c.createStatement().use { it.executeUpdate("DELETE FROM team WHERE id = ${defaultTeam["id"]}") }
            }
        }
    }

    private data class SeedV3(
        val defaultOrgId: Long,
        val acmeOrgId: Long,
        val defaultRepo: Long,
        val coreUserId: Long,
        val subA: Long,
        val subB: Long,
        val subC: Long,
        val notifA: Long,
        val notifGhost: Long,
    )

    // ── V4 → V5 ────────────────────────────────────────────────────────────

    @Test
    fun `V4 有数据升级到 V5 - 过渡期回填去重与约束收紧`() {
        val db = newTarget("v4")
        flyway(db, "4").migrate()

        val seed =
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                val orgId = one(c, "SELECT id FROM org WHERE slug = 'default'")["id"] as Long
                val projectId = insert(c, "INSERT INTO project (name, org_id, slug, visibility) VALUES (?, ?, ?, 'private')", "legacy-v4", orgId, "legacy-v4")
                val repoId =
                    insert(
                        c,
                        "INSERT INTO repo (project_id, url, branch, sync_status, org_id, external_repo_id) VALUES (?, ?, 'main', 'ok', ?, ?)",
                        projectId,
                        "file:///legacy/v4.git",
                        orgId,
                        "ext-v4",
                    )
                val coreTeam = insert(c, "INSERT INTO team (org_id, name, description) VALUES (?, 'core', '')", orgId)
                val userId =
                    insert(
                        c,
                        "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                        "v4-member",
                        "V4 Member",
                        "hash-v4-member",
                        false,
                    )
                insert(c, "INSERT INTO org_membership (user_id, org_id, org_role, status) VALUES (?, ?, 'org_member', 'active')", userId, orgId)
                // V4 过渡期：复合主键、无代理 id（没有生成键可返回）
                c.createStatement().use {
                    it.executeUpdate("INSERT INTO team_member (team_id, user_id, role) VALUES ($coreTeam, $userId, 'lead')")
                }

                // 同 endpoint + 同 team_id 的重复订阅（旧唯一键按 team 文本，允许）
                val subOld =
                    insert(
                        c,
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) VALUES (?, ?, 'GET', '/pets', 'core', ?, '')",
                        repoId,
                        orgId,
                        coreTeam,
                    )
                val subNew =
                    insert(
                        c,
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) VALUES (?, ?, 'GET', '/pets', 'core-x', ?, '')",
                        repoId,
                        orgId,
                        coreTeam,
                    )
                // B1→B2 窗口内旧代码写入：team_id 为 NULL
                val subLegacy =
                    insert(
                        c,
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, notify_url) VALUES (?, ?, 'GET', '/legacy', 'legacy', '')",
                        repoId,
                        orgId,
                    )
                val notifLegacy =
                    insert(
                        c,
                        "INSERT INTO notification (team, repo_id, org_id, method, path, summary) VALUES ('legacy', ?, ?, 'GET', '/legacy', '旧通知')",
                        repoId,
                        orgId,
                    )
                SeedV4(orgId, repoId, coreTeam, userId, subOld, subNew, subLegacy, notifLegacy)
            }

        val result = flyway(db, "5").migrate()
        assertTrue(result.migrationsExecuted >= 1, "V5 应被执行：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            // 过渡期 NULL：legacy 团队自动创建，订阅与通知回填
            val legacyTeam = one(c, "SELECT id FROM team WHERE org_id = ? AND LOWER(name) = 'legacy'", seed.orgId)
            assertEquals(legacyTeam["id"], one(c, "SELECT team_id FROM subscription WHERE id = ?", seed.subLegacy)["team_id"], "过渡期订阅回填")
            assertEquals(legacyTeam["id"], one(c, "SELECT team_id FROM notification WHERE id = ?", seed.notifLegacy)["team_id"], "过渡期通知回填")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM subscription WHERE team_id IS NULL"), "订阅无 NULL team_id")

            // 重复订阅按 (repo, method, path, team_id) 去重，保留 id 最大
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM subscription WHERE id = ?", seed.subOld), "重复订阅去重")
            assertEquals(seed.coreTeam, one(c, "SELECT team_id FROM subscription WHERE id = ?", seed.subNew)["team_id"])

            // 新唯一键：同 endpoint + 同 team_id 拒绝
            assertFailsWith<SQLException> {
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) " +
                            "VALUES (${seed.repoId}, ${seed.orgId}, 'GET', '/pets', 'core', ${seed.coreTeam}, '')",
                    )
                }
            }

            // 旧唯一键已删除、team 列可空：同 endpoint 换团队、team=NULL 均可写入
            val otherTeam = insert(c, "INSERT INTO team (org_id, name, description) VALUES (?, 'other', '')", seed.orgId)
            c.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) " +
                        "VALUES (${seed.repoId}, ${seed.orgId}, 'GET', '/pets', 'other', $otherTeam, '')",
                )
                it.executeUpdate(
                    "INSERT INTO subscription (repo_id, org_id, method, path, team, team_id, notify_url) " +
                        "VALUES (${seed.repoId}, ${seed.orgId}, 'GET', '/nullable', NULL, $otherTeam, '')",
                )
            }

            // team_member 代理主键生效，原复合键改为唯一键
            val member = one(c, "SELECT id FROM team_member WHERE team_id = ? AND user_id = ?", seed.coreTeam, seed.userId)
            assertTrue((member["id"] as Number).toLong() > 0, "team_member 应回填代理主键")
            assertFailsWith<SQLException> {
                c.createStatement().use {
                    it.executeUpdate("INSERT INTO team_member (team_id, user_id, role) VALUES (${seed.coreTeam}, ${seed.userId}, 'member')")
                }
            }
        }
    }

    private data class SeedV4(
        val orgId: Long,
        val repoId: Long,
        val coreTeam: Long,
        val userId: Long,
        val subOld: Long,
        val subNew: Long,
        val subLegacy: Long,
        val notifLegacy: Long,
    )

    // ── V5 → V6 ────────────────────────────────────────────────────────────

    private data class SeedV5(
        val orgId: Long,
        val projectId: Long,
        val adminId: Long,
        val memberId: Long,
        val teamId: Long,
    )

    @Test
    fun `V5 有数据升级到 V6 - 身份列回填与约束`() {
        val db = newTarget("v5")
        flyway(db, "5").migrate()

        val seed =
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                val orgId = one(c, "SELECT id FROM org WHERE slug = 'default'")["id"] as Long
                val projectId =
                    insert(
                        c,
                        "INSERT INTO project (name, org_id, slug, visibility) VALUES (?, ?, ?, 'private')",
                        "legacy-v5",
                        orgId,
                        "legacy-v5",
                    )
                val adminId =
                    insert(
                        c,
                        "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                        "v5-admin",
                        "V5 Admin",
                        "hash-v5-admin",
                        true,
                    )
                insert(
                    c,
                    "INSERT INTO org_membership (user_id, org_id, org_role, status) VALUES (?, ?, 'org_admin', 'active')",
                    adminId,
                    orgId,
                )
                val memberId =
                    insert(
                        c,
                        "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                        "v5-member",
                        "V5 Member",
                        "hash-v5-member",
                        false,
                    )
                insert(
                    c,
                    "INSERT INTO org_membership (user_id, org_id, org_role, status) VALUES (?, ?, 'org_member', 'active')",
                    memberId,
                    orgId,
                )
                val teamId = insert(c, "INSERT INTO team (org_id, name, description) VALUES (?, 'core', '')", orgId)
                insert(c, "INSERT INTO team_member (team_id, user_id, role) VALUES (?, ?, 'lead')", teamId, memberId)
                val repoId = insert(c, "INSERT INTO repo (project_id, url, branch) VALUES (?, 'file:///legacy-v5', 'main')", projectId)
                insert(
                    c,
                    "INSERT INTO subscription (org_id, repo_id, method, path, team_id, notify_url) VALUES (?, ?, 'GET', '/pets', ?, '')",
                    orgId,
                    repoId,
                    teamId,
                )
                SeedV5(orgId, projectId, adminId, memberId, teamId)
            }

        val result = flyway(db, "6").migrate()
        assertTrue(result.migrationsExecuted >= 1, "V6 应被执行：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            // 现有用户：status 回填 active，新身份列默认空 / false
            for (userId in listOf(seed.adminId, seed.memberId)) {
                val user =
                    one(
                        c,
                        "SELECT email, password_hash, status, last_login_at, " +
                            "CASE WHEN must_change_password THEN 1 ELSE 0 END AS must_change, " +
                            "CASE WHEN mfa_enabled THEN 1 ELSE 0 END AS mfa FROM app_user WHERE id = ?",
                        userId,
                    )
                assertNull(user["email"], "旧用户邮箱为空")
                assertNull(user["password_hash"], "旧用户密码为空")
                assertEquals("active", user["status"], "旧用户 status 回填 active")
                assertNull(user["last_login_at"])
                assertEquals(0, (user["must_change"] as Number).toInt())
                assertEquals(0, (user["mfa"] as Number).toInt())
            }
            // C1 再断言一次：现有 admin 仍是默认组织 org_admin
            assertEquals(
                "org_admin",
                one(c, "SELECT org_role FROM org_membership WHERE user_id = ?", seed.adminId)["org_role"],
            )
            // 团队数据不受影响
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM team_member WHERE team_id = ?", seed.teamId))
            // 订阅数据保留且 team_id 关系不变
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM subscription WHERE team_id = ?", seed.teamId))

            // 旧 token 逐行迁移到 user_token：legacy / 无过期 / 前缀空 / 时间保留
            assertEquals(2, count(c, "SELECT COUNT(*) AS c FROM user_token WHERE purpose = 'legacy'"))
            val legacy = one(c, "SELECT user_id, token_prefix, token_hash, purpose, expires_at FROM user_token WHERE user_id = ?", seed.adminId)
            assertEquals(seed.adminId, legacy["user_id"])
            assertEquals("", legacy["token_prefix"], "legacy 令牌无前缀信息")
            assertEquals("hash-v5-admin", legacy["token_hash"])
            assertEquals("legacy", legacy["purpose"])
            assertNull(legacy["expires_at"], "legacy 令牌不过期")
            assertEquals(
                1,
                count(
                    c,
                    "SELECT COUNT(*) AS c FROM user_token t JOIN app_user u ON u.id = t.user_id " +
                        "WHERE t.user_id = ? AND t.created_at = u.created_at",
                    seed.adminId,
                ),
                "legacy 行沿用原 created_at",
            )

            // 旧 token_hash 可空：C2 起创建的新用户不再写该列
            val newUserId = insert(c, "INSERT INTO app_user (username, display_name) VALUES ('v5-no-token', 'V5 No Token')")
            assertNull(one(c, "SELECT token_hash FROM app_user WHERE id = ?", newUserId)["token_hash"])
            assertEquals("active", one(c, "SELECT status FROM app_user WHERE id = ?", newUserId)["status"])

            // 邮箱唯一、忽略大小写
            c.prepareStatement("UPDATE app_user SET email = ? WHERE id = ?").use { ps ->
                ps.setString(1, "Admin@Example.com")
                ps.setLong(2, seed.adminId)
                ps.executeUpdate()
            }
            assertFailsWith<SQLException>("大小写不同的重复邮箱应被拒绝") {
                c.prepareStatement("UPDATE app_user SET email = ? WHERE id = ?").use { ps ->
                    ps.setString(1, "admin@example.com")
                    ps.setLong(2, seed.memberId)
                    ps.executeUpdate()
                }
            }

            // user_token：api / invite / password_reset 必须有过期时间；hash 唯一
            assertFailsWith<SQLException>("非 legacy 令牌缺少过期时间应被拒绝") {
                insert(
                    c,
                    "INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose) " +
                        "VALUES (?, 'ci', 'shu_abcd1234', 'hash-api-no-expiry', 'api')",
                    seed.memberId,
                )
            }
            insert(
                c,
                "INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose) " +
                    "VALUES (?, 'legacy-2', '', 'hash-legacy-2', 'legacy')",
                seed.memberId,
            )
            assertFailsWith<SQLException>("token_hash 应唯一") {
                insert(
                    c,
                    "INSERT INTO user_token (user_id, name, token_prefix, token_hash, purpose) " +
                        "VALUES (?, 'dup', '', 'hash-v5-admin', 'legacy')",
                    seed.memberId,
                )
            }

            // service_account：项目内名称忽略大小写唯一、角色枚举、过期时间必填
            val expiresAt = Timestamp.from(Instant.now().plusSeconds(86400))
            insert(
                c,
                "INSERT INTO service_account (org_id, project_id, name, token_prefix, token_hash, role, expires_at) " +
                    "VALUES (?, ?, 'ci-bot', 'shs_abcd1234', 'hash-sa-1', 'editor', ?)",
                seed.orgId,
                seed.projectId,
                expiresAt,
            )
            assertFailsWith<SQLException>("服务账号名称项目内忽略大小写唯一") {
                insert(
                    c,
                    "INSERT INTO service_account (org_id, project_id, name, token_prefix, token_hash, role, expires_at) " +
                        "VALUES (?, ?, 'CI-BOT', 'shs_efgh5678', 'hash-sa-2', 'viewer', ?)",
                    seed.orgId,
                    seed.projectId,
                    expiresAt,
                )
            }
            assertFailsWith<SQLException>("服务账号角色仅 viewer / editor") {
                insert(
                    c,
                    "INSERT INTO service_account (org_id, project_id, name, token_prefix, token_hash, role, expires_at) " +
                        "VALUES (?, ?, 'ci-owner', 'shs_ijkl9012', 'hash-sa-3', 'owner', ?)",
                    seed.orgId,
                    seed.projectId,
                    expiresAt,
                )
            }
            // 跨项目可同名
            val otherProject =
                insert(
                    c,
                    "INSERT INTO project (name, org_id, slug, visibility) VALUES (?, ?, ?, 'private')",
                    "legacy-v5-other",
                    seed.orgId,
                    "legacy-v5-other",
                )
            insert(
                c,
                "INSERT INTO service_account (org_id, project_id, name, token_prefix, token_hash, role, expires_at) " +
                    "VALUES (?, ?, 'ci-bot', 'shs_mnop3456', 'hash-sa-4', 'viewer', ?)",
                seed.orgId,
                otherProject,
                expiresAt,
            )

            // user_identity：(provider, subject) 唯一
            insert(
                c,
                "INSERT INTO user_identity (user_id, provider, subject, email) " +
                    "VALUES (?, 'oidc:https://idp.example', 'sub-1', 'v5-member@example.com')",
                seed.memberId,
            )
            assertFailsWith<SQLException>("同一 IdP subject 不可重复绑定") {
                insert(
                    c,
                    "INSERT INTO user_identity (user_id, provider, subject, email) " +
                        "VALUES (?, 'oidc:https://idp.example', 'sub-1', 'other@example.com')",
                    seed.adminId,
                )
            }

            // audit_event：org_id / actor_user_id 不建外键，用户 / 组织删除后审计仍可保留
            insert(
                c,
                "INSERT INTO audit_event (org_id, actor_user_id, actor_name, actor_type, action, target_type, target_id, detail, outcome, ip, user_agent) " +
                    "VALUES (?, ?, 'ghost', 'anonymous', 'auth.login.failure', 'user', '999999', 'bad credentials', 'failure', '127.0.0.1', 'test')",
                999_999L,
                999_999L,
            )
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM audit_event WHERE org_id = 999999"))
            assertFailsWith<SQLException>("actor_type 枚举") {
                insert(
                    c,
                    "INSERT INTO audit_event (org_id, actor_name, actor_type, action, outcome) " +
                        "VALUES (?, 'robot', 'robot', 'auth.login.failure', 'failure')",
                    seed.orgId,
                )
            }
            assertFailsWith<SQLException>("outcome 枚举") {
                insert(
                    c,
                    "INSERT INTO audit_event (org_id, actor_name, actor_type, action, outcome) " +
                        "VALUES (?, 'ghost', 'anonymous', 'auth.login.failure', 'maybe')",
                    seed.orgId,
                )
            }

            // 阶段 C 直接使用 Spring Session Data Redis：管理库不建会话表
            assertEquals(
                0,
                count(
                    c,
                    "SELECT COUNT(*) AS c FROM information_schema.tables " +
                        "WHERE table_schema = ${schemaExpr(db)} AND LOWER(table_name) IN ('user_session', 'spring_session')",
                ),
                "会话不落管理库",
            )
        }
    }

    // ── V6 → V7 ────────────────────────────────────────────────────────────

    @Test
    fun `V6 有数据升级到 V7 - 删除旧列且 legacy token 保留`() {
        val db = newTarget("v6v7")
        flyway(db, "5").migrate()

        val userId =
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                val orgId = one(c, "SELECT id FROM org WHERE slug = 'default'")["id"] as Long
                val uid =
                    insert(
                        c,
                        "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?, ?, ?, ?)",
                        "v7-user",
                        "V7 User",
                        "hash-v7-legacy",
                        false,
                    )
                insert(
                    c,
                    "INSERT INTO org_membership (user_id, org_id, org_role, status) VALUES (?, ?, 'org_member', 'active')",
                    uid,
                    orgId,
                )
                uid
            }

        flyway(db, "6").migrate()
        val result = flyway(db, "7").migrate()
        assertTrue(result.migrationsExecuted >= 1, "V7 应被执行：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            assertFalse(columnExists(c, db, "app_user", "token_hash"), "V7 删除 app_user.token_hash")
            assertFalse(columnExists(c, db, "app_user", "is_platform_admin"), "V7 删除 app_user.is_platform_admin")

            // 迁移前的旧 token 已回填 user_token，删列后仍可按 hash 定位
            val legacy = one(c, "SELECT user_id, purpose, expires_at FROM user_token WHERE token_hash = ?", "hash-v7-legacy")
            assertEquals(userId, legacy["user_id"])
            assertEquals("legacy", legacy["purpose"])
            assertNull(legacy["expires_at"])

            // V7 后新建用户不再依赖被删列
            val newUserId = insert(c, "INSERT INTO app_user (username, display_name) VALUES ('v7-no-token', 'V7 No Token')")
            assertEquals("active", one(c, "SELECT status FROM app_user WHERE id = ?", newUserId)["status"])
        }
    }

    @Test
    fun `V6 遇到重复 token_hash 中止迁移`() {
        val db = newTarget("v6guard")
        flyway(db, "5").migrate()

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            insert(c, "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES ('dup-a', 'Dup A', 'same-hash', FALSE)")
            insert(c, "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES ('dup-b', 'Dup B', 'same-hash', FALSE)")
        }

        val ex = assertFailsWith<FlywayException> { flyway(db, "6").migrate() }
        val message = ex.message.orEmpty()
        assertTrue(
            message.contains("chk_v6_no_duplicate_token_hash") || message.contains("重复"),
            "应因重复 token_hash 中止 V6：$message",
        )
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            assertEquals(
                0,
                count(
                    c,
                    "SELECT COUNT(*) AS c FROM information_schema.tables " +
                        "WHERE table_schema = ${schemaExpr(db)} AND table_name = 'user_token'",
                ),
                "中止后不应留下半成品 user_token",
            )
        }
    }

    @Test
    fun `V7 补记未回填 token 为 revoked legacy 并删列`() {
        val db = newTarget("v7guard")
        flyway(db, "5").migrate()

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            insert(c, "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES ('guard-old', 'Guard Old', 'hash-guard', FALSE)")
        }
        flyway(db, "6").migrate()
        // 模拟 V6 之后旧代码新写的 token_hash（没有对应 user_token 行）
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            insert(c, "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES ('late-user', 'Late User', 'hash-late', FALSE)")
        }

        val result = flyway(db, "7").migrate()
        assertTrue(result.migrationsExecuted >= 1, "V7 应被执行：${result.migrationsExecuted}")
        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            val late = one(c, "SELECT purpose, revoked_at FROM user_token WHERE token_hash = ?", "hash-late")
            assertEquals("legacy", late["purpose"])
            assertNotNull(late["revoked_at"], "未回填 token 应补记为 revoked legacy（旧值已失效）")
            assertFalse(columnExists(c, db, "app_user", "token_hash"), "V7 后旧列删除")
            assertFalse(columnExists(c, db, "app_user", "is_platform_admin"), "V7 后旧列删除")
        }
    }

    @Test
    fun `空库从 V1 直接迁移到 V7`() {
        val db = newTarget("empty")
        val result = flyway(db, "7").migrate()
        assertTrue(result.migrationsExecuted >= 7, "空库应执行 V1–V7：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM org WHERE slug = 'default'"), "bootstrap 默认组织")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM project"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM app_user"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM repo_binding"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM git_connection"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM org_membership"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team_member"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM user_token"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM service_account"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM user_identity"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM audit_event"))
            assertFalse(columnExists(c, db, "app_user", "token_hash"), "空库迁到 V7 后无旧 token 列")
            assertFalse(columnExists(c, db, "app_user", "is_platform_admin"), "空库迁到 V7 后无平台 admin 列")
            assertEquals(
                0,
                count(
                    c,
                    "SELECT COUNT(*) AS c FROM information_schema.tables " +
                        "WHERE table_schema = ${schemaExpr(db)} AND LOWER(table_name) IN ('user_session', 'spring_session')",
                ),
                "会话不落管理库",
            )
        }
    }

    // ── JDBC helpers ───────────────────────────────────────────────────────

    private fun schemaExpr(db: TestManagementDb.DbTarget): String = if (db.vendor == "postgresql") "current_schema()" else "DATABASE()"

    private fun columnExists(
        c: Connection,
        db: TestManagementDb.DbTarget,
        table: String,
        column: String,
    ): Boolean =
        count(
            c,
            "SELECT COUNT(*) AS c FROM information_schema.columns " +
                "WHERE table_schema = ${schemaExpr(db)} AND table_name = ? AND column_name = ?",
            table,
            column,
        ) > 0

    private fun rows(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): List<Map<String, Any?>> =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val md = rs.metaData
                buildList {
                    while (rs.next()) {
                        add((1..md.columnCount).associate { md.getColumnLabel(it).lowercase() to rs.getObject(it) })
                    }
                }
            }
        }

    private fun one(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): Map<String, Any?> = rows(c, sql, *args).single()

    private fun count(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): Int = (one(c, sql, *args)["c"] as Number).toInt()

    private fun insert(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): Long =
        c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                assertTrue(rs.next(), "应返回自增主键：$sql")
                // MySQL 只返回生成列（列名 id）；PostgreSQL 返回整行，首列即 id
                rs.getLong(1)
            }
        }

    private fun md5(text: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
