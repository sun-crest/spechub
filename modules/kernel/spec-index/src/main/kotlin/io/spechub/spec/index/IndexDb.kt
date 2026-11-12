package io.spechub.spec.index

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant

/**
 * SQLite 索引库（§6.4 决策 7：索引是从 gitsource 可重建的投影，不是真源）。
 * 快照语义：repo 的 api/schema 表 = 当前分支 HEAD 的一次完整投影（摄取代入 spec_hash，
 * hash 变化即整仓回填）；version 表留存每次摄入历史，供 ChangeLog / 回滚查询。
 */
class IndexDb private constructor(val conn: Connection) : AutoCloseable {

    override fun close() = conn.close()

    companion object {
        @JvmStatic
        fun open(path: java.nio.file.Path): IndexDb {
            val conn = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("PRAGMA journal_mode = WAL")
            }
            val db = IndexDb(conn)
            db.migrate()
            return db
        }

        @JvmStatic
        fun openInMemory(): IndexDb {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            conn.autoCommit = true
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
            }
            val db = IndexDb(conn)
            db.migrate()
            return db
        }
    }

    fun migrate() {
        conn.createStatement().use { st ->
            executeAll(
                st,
                """
                CREATE TABLE IF NOT EXISTS project (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    name        TEXT NOT NULL UNIQUE,
                    created_at  TEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS repo (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id   INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
                    org          TEXT NOT NULL,
                    name         TEXT NOT NULL,
                    remote_url   TEXT NOT NULL,
                    branch       TEXT NOT NULL DEFAULT 'main',
                    current_hash TEXT,
                    ingested_at  TEXT,
                    UNIQUE (project_id, org, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS version (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_id     INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                    spec_file   TEXT NOT NULL,
                    spec_hash   TEXT NOT NULL,
                    commit_sha  TEXT,
                    source      TEXT NOT NULL DEFAULT 'cli',
                    message     TEXT,
                    ingested_at TEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS api (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_id     INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                    spec_hash   TEXT NOT NULL,
                    path        TEXT NOT NULL,
                    method      TEXT NOT NULL,
                    operation_id TEXT,
                    summary     TEXT,
                    tags        TEXT DEFAULT '[]',
                    spec_version TEXT,
                    UNIQUE (repo_id, path, method)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS schema (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_id     INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                    spec_hash   TEXT NOT NULL,
                    name        TEXT NOT NULL,
                    definition  TEXT NOT NULL,
                    UNIQUE (repo_id, name)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS doc (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    repo_id     INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
                    path        TEXT NOT NULL,
                    title       TEXT,
                    content_md  TEXT,
                    updated_at  TEXT,
                    UNIQUE (repo_id, path)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_api_repo ON api(repo_id)",
                "CREATE INDEX IF NOT EXISTS idx_version_repo ON version(repo_id)",
            )
        }
    }

    private fun executeAll(st: Statement, vararg sqls: String) {
        sqls.forEach { s -> if (s.isNotBlank()) st.execute(s) }
    }

    data class ProjectRow(val id: Long, val name: String)

    data class RepoRow(
        val id: Long,
        val projectId: Long,
        val org: String,
        val name: String,
        val remoteUrl: String,
        val branch: String,
        val currentHash: String?,
        val ingestedAt: String?,
    )

    data class ApiRow(
        val id: Long,
        val repoId: Long,
        val specHash: String,
        val path: String,
        val method: String,
        val operationId: String?,
        val summary: String?,
        val tags: List<String>,
        val specVersion: String?,
    )

    data class SchemaRow(
        val id: Long,
        val repoId: Long,
        val specHash: String,
        val name: String,
        val definition: String,
    )

    data class VersionRow(
        val id: Long,
        val repoId: Long,
        val specFile: String,
        val specHash: String,
        val commitSha: String?,
        val source: String,
        val message: String?,
        val ingestedAt: String,
    )

    data class ProjectWithRepo(val project: ProjectRow, val repo: RepoRow)

    data class RepoProject(val repo: RepoRow, val project: ProjectRow)
}