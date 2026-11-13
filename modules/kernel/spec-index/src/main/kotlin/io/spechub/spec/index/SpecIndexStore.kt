package io.spechub.spec.index

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant

/**
 * 索引 DAO（含快照摄入、幂等与查询）。写操作皆是事务；查询只读。
 */
class SpecIndexStore(private val db: IndexDb) {

    private val conn: Connection get() = db.conn
    private val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val tagsType = object : TypeReference<List<String>>() {}

    // ---------------- 注册 / 查询（E2/E8） ----------------

    fun ensureProject(name: String): IndexDb.ProjectRow = conn.prepareStatement(
        "INSERT INTO project (name, created_at) VALUES (?, ?) ON CONFLICT(name) DO NOTHING"
    ).use { ps ->
        ps.setString(1, name); ps.setString(2, now()); ps.executeUpdate()
    }.let {
        findBy("SELECT * FROM project WHERE name = ?", { ps -> ps.setString(1, name) }, ::readProject)
            ?: error("project 创建失败: $name")
    }

    fun ensureRepo(project: String, org: String, name: String, remoteUrl: String, branch: String = "main"): IndexDb.RepoRow {
        val proj = ensureProject(project)
        conn.prepareStatement("""
            INSERT INTO repo (project_id, org, name, remote_url, branch)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(project_id, org, name) DO UPDATE SET remote_url = excluded.remote_url, branch = excluded.branch
        """).use { ps ->
            ps.setLong(1, proj.id); ps.setString(2, org); ps.setString(3, name)
            ps.setString(4, remoteUrl); ps.setString(5, branch); ps.executeUpdate()
        }
        return findBy("SELECT * FROM repo WHERE project_id = ? AND org = ? AND name = ?",
            { ps -> ps.setLong(1, proj.id); ps.setString(2, org); ps.setString(3, name) }, ::readRepo)
            ?: error("repo 创建失败: $org/$name")
    }

    fun listRepos(): List<IndexDb.RepoProject> =
        query("""
            SELECT r.*, p.id AS p_id, p.name AS p_name
            FROM repo r JOIN project p ON p.id = r.project_id
            ORDER BY r.id
        """) { rs ->
            // 列名带别名读 project，避免 r.* 与 p.* 的 id/name 同名列被 JDBC 取错（取第一个匹配）
            IndexDb.RepoProject(readRepo(rs), IndexDb.ProjectRow(rs.getLong("p_id"), rs.getString("p_name")))
        }

    // ---------------- 摄取快照（E3，幂等） ----------------

    /**
     * 快照式摄入：repo 的当前投影 = 一次完整回填（旧 api/schema 删除，新值插入 + version 记录）。
     * 同 hash 重复摄取短路（幂等续跑）。
     */
    @Synchronized
    fun ingestSnapshot(
        project: String,
        org: String,
        name: String,
        remoteUrl: String,
        branch: String,
        specFile: String,
        specHash: String,
        specVersion: String,
        apis: List<IndexDb.ApiRow>,
        schemas: List<IndexDb.SchemaRow>,
        commitSha: String?,
        source: String,
        message: String?,
    ): IngestResult {
        val repo = ensureRepo(project, org, name, remoteUrl, branch)
        if (repo.currentHash == specHash) return IngestResult(repo.id, unchanged = true)

        val prevAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM api WHERE repo_id = ?").use { it.setLong(1, repo.id); it.executeUpdate() }
            conn.prepareStatement("DELETE FROM schema WHERE repo_id = ?").use { it.setLong(1, repo.id); it.executeUpdate() }

            insertApis(repo.id, specHash, specVersion, apis)
            insertSchemas(repo.id, specHash, schemas)

            conn.prepareStatement(
                "INSERT INTO version (repo_id, spec_file, spec_hash, commit_sha, source, message, ingested_at) VALUES (?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setLong(1, repo.id); ps.setString(2, specFile); ps.setString(3, specHash)
                ps.setString(4, commitSha); ps.setString(5, source); ps.setString(6, message); ps.setString(7, now())
                ps.executeUpdate()
            }

            conn.prepareStatement("UPDATE repo SET current_hash = ?, ingested_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, specHash); ps.setString(2, now()); ps.setLong(3, repo.id); ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prevAutoCommit
        }
        return IngestResult(repo.id, unchanged = false)
    }

    data class IngestResult(val repoId: Long, val unchanged: Boolean)

    private fun insertApis(repoId: Long, specHash: String, specVersion: String, apis: List<IndexDb.ApiRow>) {
        if (apis.isEmpty()) return
        conn.prepareStatement(
            "INSERT INTO api (repo_id, spec_hash, path, method, operation_id, summary, tags, spec_version) VALUES (?,?,?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            apis.forEach { a ->
                ps.setLong(1, repoId); ps.setString(2, specHash); ps.setString(3, a.path); ps.setString(4, a.method)
                ps.setString(5, a.operationId); ps.setString(6, a.summary)
                ps.setString(7, json.writeValueAsString(a.tags)); ps.setString(8, specVersion)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertSchemas(repoId: Long, specHash: String, schemas: List<IndexDb.SchemaRow>) {
        if (schemas.isEmpty()) return
        conn.prepareStatement("INSERT INTO schema (repo_id, spec_hash, name, definition) VALUES (?,?,?,?)").use { ps ->
            schemas.forEach { s ->
                ps.setLong(1, repoId); ps.setString(2, specHash); ps.setString(3, s.name); ps.setString(4, s.definition)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    // ---------------- 查询（消费端） ----------------

    fun listApis(project: String, org: String, name: String): List<IndexDb.ApiRow> {
        val repo = findByProjectOrgName(project, org, name) ?: return emptyList()
        return query("SELECT * FROM api WHERE repo_id = ? ORDER BY path, method",
            { ps -> ps.setLong(1, repo.id) }, ::readApi)
    }

    fun findApi(project: String, org: String, name: String, path: String, method: String): IndexDb.ApiRow? {
        val repo = findByProjectOrgName(project, org, name) ?: return null
        return findBy("SELECT * FROM api WHERE repo_id = ? AND path = ? AND method = ?",
            { ps -> ps.setLong(1, repo.id); ps.setString(2, path); ps.setString(3, method) }, ::readApi)
    }

    fun listSchemas(project: String, org: String, name: String): List<IndexDb.SchemaRow> {
        val repo = findByProjectOrgName(project, org, name) ?: return emptyList()
        return query("SELECT * FROM schema WHERE repo_id = ? ORDER BY name",
            { ps -> ps.setLong(1, repo.id) }, ::readSchema)
    }

    fun findSchema(project: String, org: String, name: String, schemaName: String): IndexDb.SchemaRow? {
        val repo = findByProjectOrgName(project, org, name) ?: return null
        return findBy("SELECT * FROM schema WHERE repo_id = ? AND name = ?",
            { ps -> ps.setLong(1, repo.id); ps.setString(2, schemaName) }, ::readSchema)
    }

    fun versionHistory(project: String, org: String, name: String, limit: Int = 20): List<IndexDb.VersionRow> {
        val repo = findByProjectOrgName(project, org, name) ?: return emptyList()
        return query("SELECT * FROM version WHERE repo_id = ? ORDER BY id DESC LIMIT ?",
            { ps -> ps.setLong(1, repo.id); ps.setInt(2, limit) }, ::readVersion)
    }

    // ---------------- 内部 ----------------

    private fun findByProjectOrgName(project: String, org: String, name: String): IndexDb.RepoRow? =
        findBy("""
            SELECT r.* FROM repo r JOIN project p ON p.id = r.project_id
            WHERE p.name = ? AND r.org = ? AND r.name = ?
        """, { ps ->
            ps.setString(1, project); ps.setString(2, org); ps.setString(3, name)
        }, ::readRepo)

    private inline fun <T> findBy(sql: String, noinline bind: (PreparedStatement) -> Unit, map: (ResultSet) -> T): T? =
        conn.prepareStatement(sql).use { ps ->
            bind(ps)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }

    private inline fun <T> query(sql: String, noinline bind: (PreparedStatement) -> Unit, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            bind(ps)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    private fun <T> query(sql: String, map: (ResultSet) -> T): List<T> =
        conn.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    private fun readProject(rs: ResultSet) = IndexDb.ProjectRow(rs.getLong("id"), rs.getString("name"))
    private fun readRepo(rs: ResultSet) = IndexDb.RepoRow(
        rs.getLong("id"), rs.getLong("project_id"), rs.getString("org"), rs.getString("name"),
        rs.getString("remote_url"), rs.getString("branch"), rs.getString("current_hash"), rs.getString("ingested_at"),
    )
    private fun readApi(rs: ResultSet) = IndexDb.ApiRow(
        rs.getLong("id"), rs.getLong("repo_id"), rs.getString("spec_hash"), rs.getString("path"),
        rs.getString("method"), rs.getString("operation_id"), rs.getString("summary"),
        json.readValue(rs.getString("tags"), tagsType), rs.getString("spec_version"),
    )
    private fun readSchema(rs: ResultSet) = IndexDb.SchemaRow(
        rs.getLong("id"), rs.getLong("repo_id"), rs.getString("spec_hash"),
        rs.getString("name"), rs.getString("definition"),
    )
    private fun readVersion(rs: ResultSet) = IndexDb.VersionRow(
        rs.getLong("id"), rs.getLong("repo_id"), rs.getString("spec_file"), rs.getString("spec_hash"),
        rs.getString("commit_sha"), rs.getString("source"), rs.getString("message"), rs.getString("ingested_at"),
    )

    private fun now(): String = Instant.now().toString()
}