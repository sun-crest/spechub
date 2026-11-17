package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals

/**
 * 旧库迁移（Phase 2 收尾）：管理域已全部迁管理库，旧 SQLite 过渡表与旧 project/repo 必须被清理，
 * 投影表（version/api/schema/doc）重建为空（可从 git 全量重建）。
 */
class IndexDbMigrationTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `旧库迁移 - 清理过渡管理表与旧 project-repo，投影表重建为空`() {
        val path = temp.resolve("legacy.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                // 旧版表结构（含跨边界外键）
                st.execute("CREATE TABLE project (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")
                st.execute("CREATE TABLE repo (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL REFERENCES project(id))")
                st.execute("CREATE TABLE version (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), path TEXT)")
                st.execute("CREATE TABLE api (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), version_id INTEGER REFERENCES version(id))")
                st.execute("CREATE TABLE schema (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), version_id INTEGER REFERENCES version(id), stage TEXT)")
                st.execute("CREATE TABLE doc (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), version_id INTEGER REFERENCES version(id))")
                st.execute("CREATE TABLE comment (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), doc_id INTEGER REFERENCES doc(id))")
                st.execute("CREATE TABLE mock_rule (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id), api_id INTEGER REFERENCES api(id))")
                st.execute("CREATE TABLE changelog (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER REFERENCES repo(id))")
                st.execute("CREATE TABLE subscription (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id))")
                st.execute("CREATE TABLE notification (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id))")
                st.execute("CREATE TABLE review (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_id INTEGER NOT NULL REFERENCES repo(id))")
                st.execute("CREATE TABLE app_user (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT)")
                st.execute("CREATE TABLE membership (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER REFERENCES app_user(id), project_id INTEGER REFERENCES project(id))")
                // 旧数据（迁移时按存量重置丢弃）
                st.execute("INSERT INTO project (id, name) VALUES (1, 'default')")
                st.execute("INSERT INTO repo (id, project_id) VALUES (1, 1)")
                st.execute("INSERT INTO version (id, repo_id, path) VALUES (1, 1, 'specs/a.yaml')")
                st.execute("INSERT INTO api (id, repo_id, version_id) VALUES (1, 1, 1)")
                st.execute("INSERT INTO comment (id, repo_id, doc_id) VALUES (1, 1, NULL)")
            }
        }

        IndexDb(path).migrate()

        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                for (table in listOf(
                    "project", "repo", "comment", "mock_rule", "changelog",
                    "subscription", "notification", "review", "app_user", "membership",
                )) {
                    assertEquals(false, tableExists(st, table), "迁移后不应存在旧表 $table")
                }
                for (table in listOf("version", "api", "schema", "doc")) {
                    assertEquals(true, tableExists(st, table), "投影表 $table 应重建")
                }
                st.executeQuery("SELECT COUNT(*) FROM version").use { rs -> rs.next(); assertEquals(0, rs.getInt(1), "投影表应为空（从 git 重建）") }
                st.executeQuery("SELECT COUNT(*) FROM api").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        }
    }

    private fun tableExists(st: java.sql.Statement, table: String): Boolean =
        st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { it.next() }
}
