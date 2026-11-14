package io.spechub.spec.index

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/*
 * IndexDb —— SQLite 连接 / 迁移 / 事务执行器。
 *
 * 定位（§6.4 决策 7）：SQLite 只是真源 git 的可重建投影（索引），一切业务写最终落 git 文件；
 * 索引随时可从 git 全量重建，损坏/丢失不构成数据事故。因此：
 * - 全表声明幂等 DDL（migrate 即建）；不做破坏性迁移版本表自找麻烦，重建纪律兜底。
 * - 每操作独立连接（WAL 并发读 + 单写安全），事务块为单位提交。
 * - 引用完整性 foreign_keys 开启（ON DELETE CASCADE 清理 repo 下投影）。
 */

class IndexDb(private val path: Path) {

    private val url: String = "jdbc:sqlite:${path.toAbsolutePath()}"

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
    }

    /** 应用全部 DDL（幂等，可安全多次调用） */
    fun migrate() {
        transaction { conn ->
            conn.exec(IndexSchema.DDL_STATEMENTS)
        }
    }

    /** 在一个事务内执行块，成功提交 / 异常回滚并抛 */
    fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { conn ->
            // 连接级 PRAGMA 必须在事务外生效（foreign_keys 在事务中更改会被 SQLite 静默忽略 → 级联失效）
            conn.exec(listOf(
                "PRAGMA foreign_keys=ON",
                "PRAGMA busy_timeout=5000",
                "PRAGMA journal_mode=WAL",
            ))
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }

    /** 快速点到点执行（单条语句，事务块内） */
    fun execute(sql: String, vararg params: Any?): Int =
        transaction { conn ->
            val st = conn.prepareStatement(sql)
            try {
                params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.executeUpdate()
            } finally {
                st.close()
            }
        }

    fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> =
        transaction { conn ->
            val st = conn.prepareStatement(sql)
            try {
                params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                val rs = st.executeQuery()
                try {
                    val meta = rs.metaData
                    val cols = meta.columnCount
                    val rows = mutableListOf<Map<String, Any?>>()
                    while (rs.next()) {
                        val row = HashMap<String, Any?>(cols)
                        for (i in 1..cols) {
                            row[meta.getColumnLabel(i)] = rs.getObject(i)
                        }
                        rows += row
                    }
                    rows
                } finally {
                    rs.close()
                }
            } finally {
                st.close()
            }
        }

    private fun Connection.exec(sqls: List<String>) {
        for (sql in sqls) this.createStatement().use { it.execute(sql) }
    }
}

/** 表结构真源（幂等 DDL）。列名一律 snake_case；时间一律 ISO-8601 文本。 */
object IndexSchema {

    val DDL_STATEMENTS: List<String> = listOf(
        // 项目空间（P0 单一默认 project，P1 RBAC 扩展）
        """
        CREATE TABLE IF NOT EXISTS project (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            UNIQUE(name)
        )
        """.trimIndent(),

        // 后端代码仓（spec 来源）
        """
        CREATE TABLE IF NOT EXISTS repo (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
            url TEXT NOT NULL,
            branch TEXT NOT NULL DEFAULT 'main',
            sync_status TEXT NOT NULL DEFAULT 'new',
            last_error TEXT,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            UNIQUE(project_id, url)
        )
        """.trimIndent(),

        // 文档发布快照（P2 I6 draft/release 双层版本底座；specHash 幂等）
        """
        CREATE TABLE IF NOT EXISTS version (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            path TEXT NOT NULL,
            spec_hash TEXT NOT NULL,
            stage TEXT NOT NULL DEFAULT 'draft',
            parsed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            UNIQUE(repo_id, path, spec_hash)
        )
        """.trimIndent(),

        // 归一化接口条目（method+path 唯一，同一 repo 内幂等重建）
        """
        CREATE TABLE IF NOT EXISTS api (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
            method TEXT NOT NULL,
            path TEXT NOT NULL,
            operation_id TEXT,
            tag TEXT,
            summary TEXT,
            spec_json TEXT NOT NULL,
            UNIQUE(repo_id, method, path)
        )
        """.trimIndent(),

        // 项目级实体 schema（components.schemas 投影；api 的 $ref 指向由 spec_json 内可解析）
        """
        CREATE TABLE IF NOT EXISTS schema (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            spec_json TEXT NOT NULL,
            UNIQUE(repo_id, name)
        )
        """.trimIndent(),

        // Doc 域（P2 K 组启用；占位建表保持实体齐全，见 §6.3）
        """
        CREATE TABLE IF NOT EXISTS doc (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
            path TEXT NOT NULL,
            title TEXT NOT NULL,
            markdown TEXT NOT NULL,
            UNIQUE(repo_id, path)
        )
        """.trimIndent(),

        // 查询索引
        "CREATE INDEX IF NOT EXISTS idx_repo_url ON repo(url)",
        "CREATE INDEX IF NOT EXISTS idx_version_spec_hash ON version(spec_hash)",
        "CREATE INDEX IF NOT EXISTS idx_api_method_path ON api(method, path)",
        "CREATE INDEX IF NOT EXISTS idx_api_tag ON api(tag)",
    )
}