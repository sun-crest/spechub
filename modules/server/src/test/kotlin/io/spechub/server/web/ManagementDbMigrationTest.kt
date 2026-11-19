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
 * A9 迁移矩阵（PostgreSQL / MySQL 由 `-Dspechub.test.db` 选择）：
 * - V2 有数据 → V3：org / project / repo / repo_binding / 租户列全部回填，单主约束生效；
 * - 空库 V1 → V3：三版一次性迁移成功，bootstrap 默认组织就绪。
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

    // ── V2 → V3 ────────────────────────────────────────────────────────────

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
    fun `V2 有数据升级到 V3 - 组织项目仓库绑定与租户列全部回填`() {
        val db = newTarget("v2")
        flyway(db, "2").migrate()
        val seed = seedV2(db)

        val result = flyway(db, null).migrate()
        assertTrue(result.migrationsExecuted >= 1, "V3 应被执行：${result.migrationsExecuted}")

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
        }
    }

    @Test
    fun `空库从 V1 直接迁移到 V3`() {
        val db = newTarget("empty")
        val result = flyway(db, null).migrate()
        assertTrue(result.migrationsExecuted >= 3, "空库应执行 V1–V3：${result.migrationsExecuted}")

        DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
            assertEquals(1, count(c, "SELECT COUNT(*) AS c FROM org WHERE slug = 'default'"), "bootstrap 默认组织")
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM project"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM app_user"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM repo_binding"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM git_connection"))
            assertEquals(0, count(c, "SELECT COUNT(*) AS c FROM org_membership"))
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
