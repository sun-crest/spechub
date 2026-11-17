package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * 旧库迁移：comment.doc_id / mock_rule.api_id 曾是指向投影表的外键（ON DELETE CASCADE），
 * migrate 必须原地重建管理表并保留全部行，而不是 DROP 丢数据。
 */
class IndexDbMigrationTest {

    @TempDir
    lateinit var temp: Path

    private val legacyCommentDdl = """
        CREATE TABLE comment (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER,
            doc_id INTEGER REFERENCES doc(id) ON DELETE CASCADE,
            method TEXT NOT NULL DEFAULT '',
            path TEXT NOT NULL DEFAULT '',
            author TEXT NOT NULL,
            body TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()

    private val legacyMockRuleDdl = """
        CREATE TABLE mock_rule (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
            api_id INTEGER REFERENCES api(id) ON DELETE CASCADE,
            field_pattern TEXT NOT NULL,
            rule_type TEXT NOT NULL,
            rule_value TEXT NOT NULL DEFAULT '',
            created_at TEXT NOT NULL DEFAULT ''
        )
    """.trimIndent()

    @Test
    fun `旧库外键 migrate 原地重建 - 管理数据保留且外键移除`() {
        val path = temp.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                // 先用当前 DDL 建齐全部表，再把两张管理表替换成带跨边界外键的旧版
                IndexSchema.DDL_STATEMENTS.forEach(st::execute)
                st.execute("DROP TABLE comment")
                st.execute("DROP TABLE mock_rule")
                st.execute(legacyCommentDdl)
                st.execute(legacyMockRuleDdl)
                // 前置数据（满足旧外键）
                st.execute("INSERT INTO project (id, name) VALUES (1, 'default')")
                st.execute("INSERT INTO repo (id, project_id, url, branch, sync_status) VALUES (1, 1, 'u', 'main', 'ok')")
                st.execute("INSERT INTO version (id, repo_id, path, spec_hash, stage) VALUES (1, 1, 'specs/a.yaml', 'h', 'draft')")
                st.execute("INSERT INTO api (id, repo_id, version_id, method, path, spec_json, stage) VALUES (1, 1, 1, 'GET', '/x', '{}', 'draft')")
                st.execute("INSERT INTO doc (id, repo_id, version_id, path, title, markdown) VALUES (1, 1, 1, 'docs/a.md', 't', 'm')")
                st.execute("INSERT INTO comment (id, repo_id, api_id, doc_id, method, path, author, body) VALUES (1, 1, 1, 1, '', 'docs/a.md', 'alice', '文档评论')")
                st.execute("INSERT INTO mock_rule (id, repo_id, api_id, field_pattern, rule_type, rule_value) VALUES (1, 1, 1, 'name', 'value', 'x')")
            }
        }

        IndexDb(path).migrate()

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                // 数据保留（原地重建，不是 DROP 重建）
                st.executeQuery("SELECT body FROM comment WHERE id=1").use { rs ->
                    rs.next()
                    assertEquals("文档评论", rs.getString(1), "评论行应保留")
                }
                st.executeQuery("SELECT rule_value FROM mock_rule WHERE id=1").use { rs ->
                    rs.next()
                    assertEquals("x", rs.getString(1), "mock 规则行应保留")
                }
                // 外键已移除：comment 不再引用 doc、mock_rule 不再引用 api
                assertEquals(emptyList(), foreignKeyTargets(st, "comment").filter { it == "doc" }, "comment.doc_id 不应再有 FK")
                assertEquals(emptyList(), foreignKeyTargets(st, "mock_rule").filter { it == "api" }, "mock_rule.api_id 不应再有 FK")
                // 删除投影行不再级联删管理数据
                st.execute("DELETE FROM doc")
                st.execute("DELETE FROM api")
                st.executeQuery("SELECT COUNT(*) FROM comment").use { rs -> rs.next(); assertEquals(1, rs.getInt(1), "评论不应被级联删除") }
                st.executeQuery("SELECT COUNT(*) FROM mock_rule").use { rs -> rs.next(); assertEquals(1, rs.getInt(1), "mock 规则不应被级联删除") }
            }
        }
    }

    private fun foreignKeyTargets(st: java.sql.Statement, table: String): List<String> {
        val targets = mutableListOf<String>()
        st.executeQuery("PRAGMA foreign_key_list($table)").use { rs ->
            while (rs.next()) targets += rs.getString("table")
        }
        return targets
    }
}
