package io.spechub.server.web

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A9/B1 迁移矩阵（PostgreSQL / MySQL 由 `-Dspechub.test.db` 选择）：
 * - V2 有数据 → V4：org / project / repo / repo_binding / 租户列 / 团队与订阅归属全部回填；
 * - V3 有数据 → V4：同 org 同名团队合并、跨 org 同名隔离、成员启发式回填；
 * - 空库 V1 → V4：四版一次性迁移成功，bootstrap 默认组织就绪。
 *
 * 不走 Spring：直接 Testcontainers 目标库 + Flyway 编程式迁移，避免 @SpringBootTest 的 Flyway 自动装配。
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
    fun `V2 有数据升级到 V4 - 组织项目仓库绑定与团队订阅全部回填`() {
        val db = newTarget("v2")
        flyway(db, "2").migrate()
        val seed = seedV2(db)

        val result = flyway(db, null).migrate()
        assertTrue(result.migrationsExecuted >= 2, "V3/V4 应被执行：${result.migrationsExecuted}")

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

            // 组织成员关系：平台 admin → org_admin，普通用户 → org_member
            assertEquals(2, count(c, "SELECT COUNT(*) AS c FROM org_membership"))
            assertEquals("org_admin", one(c, "SELECT org_role FROM org_membership WHERE user_id = ?", seed.adminId)["org_role"])
            assertEquals("org_member", one(c, "SELECT org_role FROM org_membership WHERE user_id = ?", seed.userId)["org_role"])

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

            // 过渡期保留旧唯一键：同 endpoint + 同旧 team 文本的重复订阅仍被拒绝（V5 再切换为 team_id）
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

        val result = flyway(db, null).migrate()
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

    @Test
    fun `空库从 V1 直接迁移到 V4`() {
        val db = newTarget("empty")
        val result = flyway(db, null).migrate()
        assertTrue(result.migrationsExecuted >= 4, "空库应执行 V1–V4：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM org WHERE slug = 'default'"), "bootstrap 默认组织")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM project"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM app_user"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM repo_binding"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM git_connection"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM org_membership"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM team_member"))
        }
    }

    // ── JDBC helpers ───────────────────────────────────────────────────────

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
