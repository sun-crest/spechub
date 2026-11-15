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

    /** 应用全部 DDL（幂等，可安全多次调用）。
     *  旧库升级：P0 库的 api/schema 表无 stage 列（I6 加列 + 改复合幂等键，SQLite 无法 ADD COLUMN 改 UNIQUE），
     *  按 §6.4 决策 7「索引可重建」纪律先 drop 再随 DDL 重建（投影可从 git 全量重建，不构成数据事故）。
     *  顺序注意：引用 stage 的索引必须先于 DDL 完成表重建，否则旧表缺列建索引报错。
     *  I5：comment 需 method/path 稳定寻址列且 api_id 去 FK（投影重建不级联删）；旧表缺列则连 changelog 重建。 */
    fun migrate() {
        transaction { conn ->
            if (!hasColumn(conn, "api", "stage") || !hasColumn(conn, "schema", "stage")) {
                conn.exec(listOf("DROP TABLE IF EXISTS api", "DROP TABLE IF EXISTS schema"))
            }
            if (!hasColumn(conn, "comment", "method")) {
                conn.exec(listOf("DROP TABLE IF EXISTS comment", "DROP TABLE IF EXISTS changelog"))
            }
            conn.exec(IndexSchema.DDL_STATEMENTS)

            // 全文检索 FTS5（K2）：环境（sqlite-jdbc FTS5 编译开关）支持则建 doc_fts trigram 虚拟表
            try {
                conn.exec(listOf(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(title, body, tokenize='trigram')",
                ))
                fts5Available = true
            } catch (e: java.sql.SQLException) {
                fts5Available = false // 不支持 FTS5 → 检索回退 LIKE（中文子串仍可达）
            }
        }
    }

    var fts5Available: Boolean = false
        private set

    private fun hasColumn(conn: java.sql.Connection, table: String, column: String): Boolean {
        val st = conn.prepareStatement("PRAGMA table_info(${table})")
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

    /** 在一个事务内执行块，成功提交 / 异常回滚并抛 */
    fun <T> transaction(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use { conn ->
            // 连接级 PRAGMA 必须在事务外生效（foreign_keys 在事务中更改会被 SQLite 静默忽略 → 级联失效）
            conn.exec(listOf(
                "PRAGMA foreign_keys=ON",
                // 20s：多 @SpringBootTest context 顺序/并行初始化时偶发 SQLITE_BUSY（WAL 写锁竞争）容忍窗口
                "PRAGMA busy_timeout=20000",
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

        // 归一化接口条目（method+path 唯一；I6 加 stage 维度后同 repo 可并存 draft/release 双投影）
        // 幂等读档区分：replaceApisForRepo 只清 draft，release 快照副本独立保留（§6.4 决策 5）
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
            stage TEXT NOT NULL DEFAULT 'draft',
            UNIQUE(repo_id, method, path, stage)
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
            stage TEXT NOT NULL DEFAULT 'draft',
            UNIQUE(repo_id, name, stage)
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

        // Mock 规则（I2，§6.3 MockRule：pattern / value / script 三档）
        """
        CREATE TABLE IF NOT EXISTS mock_rule (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER REFERENCES api(id) ON DELETE CASCADE,
            field_pattern TEXT NOT NULL,
            rule_type TEXT NOT NULL,
            rule_value TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )
        """.trimIndent(),

        // 变更记录（I5 铺底，§6.3 ChangeLog：actor/diff/reason/review）
        // api_id 非 FK：避免摄取重建 api 投影（DELETE+INSERT）时评论类留痕被级联删；repo/method/path 稳定寻址。
        """
        CREATE TABLE IF NOT EXISTS changelog (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER,
            actor TEXT NOT NULL,
            action TEXT NOT NULL,
            detail TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )
        """.trimIndent(),

        // 平台用户（I4 RBAC：认证对象；token 只存 hash，平台管理员由 admin-token bootstrap）
        """
        CREATE TABLE IF NOT EXISTS app_user (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            display_name TEXT NOT NULL DEFAULT '',
            token_hash TEXT NOT NULL,
            is_platform_admin INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )
        """.trimIndent(),

        // 项目成员（I4：user → project 的角色，owner/admin/editor/viewer）
        """
        CREATE TABLE IF NOT EXISTS membership (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
            project_id INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,
            role TEXT NOT NULL,
            UNIQUE(user_id, project_id)
        )
        """.trimIndent(),

        // 评论（I5：挂在接口/文档/仓库下的轻评论；作者 = app_user.username）
        // 关键设计：api 是可重建投影（摄取 DELETE+INSERT），评论不挂 api 行 FK 级联，
        // 用 repo_id + method + path 稳定寻址（api_id 仅作展示快照，过时无害）。
        """
        CREATE TABLE IF NOT EXISTS comment (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER,
            doc_id INTEGER REFERENCES doc(id) ON DELETE CASCADE,
            method TEXT NOT NULL DEFAULT '',
            path TEXT NOT NULL DEFAULT '',
            author TEXT NOT NULL,
            body TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )
        """.trimIndent(),

        // 订阅（I7）：消费团队声明依赖某接口 → 结构变更时通知（§6.3 Subscription）。
        // 稳定寻址同 comment：repo + method + path（api 是可重建投影）；api_id 非 FK。
        """
        CREATE TABLE IF NOT EXISTS subscription (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER,
            method TEXT NOT NULL,
            path TEXT NOT NULL,
            team TEXT NOT NULL,
            notify_url TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            UNIQUE(repo_id, method, path, team)
        )
        """.trimIndent(),

        // 变更通知（I7）：摄取检测订阅接口结构变化时落条，消费团队可查（通知历史留痕）
        """
        CREATE TABLE IF NOT EXISTS notification (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            team TEXT NOT NULL,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            method TEXT NOT NULL,
            path TEXT NOT NULL,
            summary TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
        )
        """.trimIndent(),

        // 评审审批流（I8「写操作皆审」§6.4 决策 3）：spec 写回先入 pending，人批准才落真源。
        // new_content 存待批准全文；approve 时由 SpecWriter 写入真源（git commit+push + 摄取）。
        """
        CREATE TABLE IF NOT EXISTS review (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            path TEXT NOT NULL,
            new_content TEXT NOT NULL,
            diff TEXT NOT NULL DEFAULT '',
            old_hash TEXT,
            status TEXT NOT NULL DEFAULT 'pending',
            requester TEXT NOT NULL,
            reviewer TEXT,
            reject_reason TEXT,
            created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            resolved_at TEXT
        )
        """.trimIndent(),

        // 查询索引
        "CREATE INDEX IF NOT EXISTS idx_repo_url ON repo(url)",
        "CREATE INDEX IF NOT EXISTS idx_version_spec_hash ON version(spec_hash)",
        "CREATE INDEX IF NOT EXISTS idx_api_method_path ON api(method, path)",
        "CREATE INDEX IF NOT EXISTS idx_api_tag ON api(tag)",
        "CREATE INDEX IF NOT EXISTS idx_api_stage ON api(stage)",
        "CREATE INDEX IF NOT EXISTS idx_mock_rule_repo ON mock_rule(repo_id)",
        "CREATE INDEX IF NOT EXISTS idx_changelog_repo ON changelog(repo_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_app_user_username ON app_user(username)",
        "CREATE INDEX IF NOT EXISTS idx_membership_user ON membership(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_comment_api ON comment(api_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_comment_repo ON comment(repo_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_subscription_endpoint ON subscription(repo_id, method, path)",
        "CREATE INDEX IF NOT EXISTS idx_notification_team ON notification(team, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_review_repo_status ON review(repo_id, status)",
    )
}