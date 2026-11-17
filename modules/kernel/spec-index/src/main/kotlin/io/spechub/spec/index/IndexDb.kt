package io.spechub.spec.index

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/*
 * IndexDb —— SQLite 连接 / 迁移 / 事务执行器（投影层专用）。
 *
 * 定位（§6.4 决策 7）：SQLite 只是真源 git 的可重建投影（索引），一切业务写最终落 git 文件；
 * 索引随时可从 git 全量重建，损坏/丢失不构成数据事故。因此：
 * - 全表声明幂等 DDL（migrate 即建）；不做破坏性迁移版本表自找麻烦，重建纪律兜底。
 * - 每操作独立连接（WAL 并发读 + 单写安全），事务块为单位提交。
 * - Phase 2 起管理数据（用户/项目/仓库/评论/订阅/评审/mock 规则/changelog/发布状态）全部在管理库；
 *   本库只保留版本快照与 api/schema/doc 投影，repo_id 均为逻辑列（无跨库外键）。
 */

class IndexDb(
    private val path: Path,
) {
    private val url: String = "jdbc:sqlite:${path.toAbsolutePath()}"

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
    }

    /**
     * 应用全部 DDL（幂等，可安全多次调用）。
     * 旧库升级：P0 库的 api/schema 表无 stage 列（I6 加列 + 改复合幂等键，SQLite 无法 ADD COLUMN 改 UNIQUE），
     * 按 §6.4 决策 7「索引可重建」纪律先 drop 再随 DDL 重建（投影可从 git 全量重建，不构成数据事故）。
     */
    fun migrate() {
        transaction { conn ->
            if (!hasColumn(conn, "api", "stage") || !hasColumn(conn, "schema", "stage")) {
                conn.exec(listOf("DROP TABLE IF EXISTS api", "DROP TABLE IF EXISTS schema"))
            }

            // Phase 2 收尾：管理域全部迁入管理库 → 清理 SQLite 过渡表（含旧 project/repo）。
            // 先于 project/repo 删除（旧表带有指向它们的外键）。
            val hadLegacyRepo = tableExists(conn, "repo")
            conn.exec(
                listOf(
                    "DROP TABLE IF EXISTS comment",
                    "DROP TABLE IF EXISTS mock_rule",
                    "DROP TABLE IF EXISTS changelog",
                    "DROP TABLE IF EXISTS subscription",
                    "DROP TABLE IF EXISTS notification",
                    "DROP TABLE IF EXISTS review",
                    "DROP TABLE IF EXISTS membership",
                    "DROP TABLE IF EXISTS app_user",
                ),
            )
            if (hadLegacyRepo) {
                // 旧投影表（api/schema/doc/version）带 repo 外键 → 一并 DROP 重建（投影可重建）
                conn.exec(
                    listOf(
                        "DROP TABLE IF EXISTS doc",
                        "DROP TABLE IF EXISTS api",
                        "DROP TABLE IF EXISTS schema",
                        "DROP TABLE IF EXISTS version",
                    ),
                )
            }
            conn.exec(listOf("DROP TABLE IF EXISTS repo", "DROP TABLE IF EXISTS project"))
            conn.exec(IndexSchema.DDL_STATEMENTS)

            // 全文检索 FTS5（K2）：环境（sqlite-jdbc FTS5 编译开关）支持则建 doc_fts trigram 虚拟表
            try {
                conn.exec(
                    listOf(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(title, body, tokenize='trigram')",
                    ),
                )
                fts5Available = true
            } catch (e: java.sql.SQLException) {
                fts5Available = false // 不支持 FTS5 → 检索回退 LIKE（中文子串仍可达）
            }
        }
    }

    var fts5Available: Boolean = false
        private set

    private fun hasColumn(
        conn: java.sql.Connection,
        table: String,
        column: String,
    ): Boolean {
        val st = conn.prepareStatement("PRAGMA table_info($table)")
        try {
            val rs = st.executeQuery()
            try {
                while (rs.next()) if (rs.getString("name") == column) return true
            } finally {
                rs.close()
            }
        } finally {
            st.close()
        }
        return false
    }

    /** 表是否存在（迁移期检测旧库结构） */
    private fun tableExists(
        conn: java.sql.Connection,
        table: String,
    ): Boolean = conn.queryObj("SELECT name FROM sqlite_master WHERE type='table' AND name=?", table) != null

    /** 在一个事务内执行块，成功提交 / 异常回滚并抛 */
    fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { conn ->
            // 连接级 PRAGMA 必须在事务外生效（foreign_keys 在事务中更改会被 SQLite 静默忽略 → 级联失效）
            conn.exec(
                listOf(
                    "PRAGMA foreign_keys=ON",
                    // 20s：多 @SpringBootTest context 顺序/并行初始化时偶发 SQLITE_BUSY（WAL 写锁竞争）容忍窗口
                    "PRAGMA busy_timeout=20000",
                    "PRAGMA journal_mode=WAL",
                ),
            )
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
    fun execute(
        sql: String,
        vararg params: Any?,
    ): Int =
        transaction { conn ->
            val st = conn.prepareStatement(sql)
            try {
                params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                st.executeUpdate()
            } finally {
                st.close()
            }
        }

    fun query(
        sql: String,
        vararg params: Any?,
    ): List<Map<String, Any?>> =
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

/** 投影表结构真源（幂等 DDL）。列名一律 snake_case；时间一律 ISO-8601 文本。 */
object IndexSchema {
    val DDL_STATEMENTS: List<String> =
        listOf(
            // 文档发布快照（P2 I6 draft/release 双层版本底座；specHash 幂等）
            """
            CREATE TABLE IF NOT EXISTS version (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id INTEGER NOT NULL,
                path TEXT NOT NULL,
                spec_hash TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'draft',
                parsed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                UNIQUE(repo_id, path, spec_hash)
            )
            """.trimIndent(),
            // 归一化接口条目（method+path 唯一；I6 加 stage 维度后同 repo 可并存 draft/release 双投影）
            // 幂等读档区分：replaceApisForRepo 只清 draft，release 快照副本独立保留（§6.4 决策 5）
            """
            CREATE TABLE IF NOT EXISTS api (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id INTEGER NOT NULL,
                version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                operation_id TEXT,
                tag TEXT,
                summary TEXT,
                spec_json TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'draft',
                UNIQUE(repo_id, method, path, stage)
            )
            """.trimIndent(),
            // 项目级实体 schema（components.schemas 投影；api 的 $ref 指向由 spec_json 内可解析）
            """
            CREATE TABLE IF NOT EXISTS schema (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id INTEGER NOT NULL,
                version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                spec_json TEXT NOT NULL,
                stage TEXT NOT NULL DEFAULT 'draft',
                UNIQUE(repo_id, name, stage)
            )
            """.trimIndent(),
            // Doc 域（P2 K 组启用；占位建表保持实体齐全，见 §6.3）
            """
            CREATE TABLE IF NOT EXISTS doc (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                repo_id INTEGER NOT NULL,
                version_id INTEGER REFERENCES version(id) ON DELETE CASCADE,
                path TEXT NOT NULL,
                title TEXT NOT NULL,
                markdown TEXT NOT NULL,
                UNIQUE(repo_id, path)
            )
            """.trimIndent(),
            // 查询索引
            "CREATE INDEX IF NOT EXISTS idx_version_spec_hash ON version(spec_hash)",
            "CREATE INDEX IF NOT EXISTS idx_api_method_path ON api(method, path)",
            "CREATE INDEX IF NOT EXISTS idx_api_tag ON api(tag)",
            "CREATE INDEX IF NOT EXISTS idx_api_stage ON api(stage)",
        )
}
