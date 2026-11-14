package io.spechub.spec.index

import java.sql.Connection

/*
 * SpecIndexStore —— 索引 DAO（E4）。
 *
 * 写入纪律（§6.4 决策 7）：
 * - 「写入单口」：业务写最终落 git 文件，本 DAO 只做投影维护；
 * - replaceXxxForRepo：摄取某仓时「先清该仓投影 → 再整批插入」，天然幂等、可整体重建（reindex）。
 * - 幂等键：repo(project_id,url)、version(repo_id,path,spec_hash)、api(repo_id,method,path)、schema(repo_id,name)。
 */

class SpecIndexStore(private val db: IndexDb) {

    // ── project ──────────────────────────────────────────────────────────

    fun upsertProject(name: String): Long = db.transaction { conn ->
        insertProject(conn, name)
    }

    fun projectIdByName(name: String): Long? = db.transaction { conn ->
        (conn.queryObj("SELECT id FROM project WHERE name=?", name) as? Number)?.toLong()
    }

    fun listProjects(): List<ProjectRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM project ORDER BY id") { r ->
            ProjectRecord((r["id"] as Number).toLong(), r["name"] as String)
        }
    }

    fun getApiById(id: Long): ApiRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM api WHERE id=?", id) { r ->
            ApiRecord(
                (r["id"] as Number).toLong(), r.long("repo_id"), r.longOrNull("version_id"),
                r["method"] as String, r["path"] as String,
                r["operation_id"] as? String, r["tag"] as? String, r["summary"] as? String,
                r["spec_json"] as String,
            )
        }.firstOrNull()
    }

    // ── repo ─────────────────────────────────────────────────────────────

    fun insertRepo(projectName: String, url: String, branch: String = "main"): RepoRecord =
        db.transaction { conn ->
            val pid = projectIdByName(conn, projectName)
                ?: insertProject(conn, projectName)
            // 同 URL 幂等返回既存
            getRepoByUrl(conn, pid, url)?.let { return@transaction it }
            val id = conn.insert(
                "INSERT INTO repo (project_id, url, branch, sync_status) VALUES (?,?,?,?)",
                pid, url, branch, SyncStatus.NEW,
            )
            RepoRecord(id, pid, url, branch, SyncStatus.NEW)
        }

    fun listRepos(): List<RepoRecord> = db.transaction { conn ->
        conn.select(
            "SELECT repo.*, project.name AS project_name FROM repo JOIN project ON project.id=repo.project_id",
        ) { r -> RepoRecord(r.long("id"), r.long("project_id"), r["url"] as String, r["branch"] as String, r["sync_status"] as String, r["last_error"] as? String) }
    }

    fun getRepo(id: Long): RepoRecord? = db.transaction { conn ->
        getRepoById(conn, id)
    }

    fun updateSyncStatus(repoId: Long, status: String, error: String? = null) = db.transaction { conn ->
        conn.exec("UPDATE repo SET sync_status=?, last_error=? WHERE id=?", status, error, repoId)
    }

    // ── version（发布快照）───────────────────────────────────────────────

    /** 同文档同 hash 幂等：已存在则返回既有行，否则新建 */
    fun upsertVersion(repoId: Long, path: String, specHash: String): VersionRecord = db.transaction { conn ->
        getVersion(conn, repoId, path, specHash)?.let { return@transaction it }
        val id = conn.insert(
            "INSERT INTO version (repo_id, path, spec_hash) VALUES (?,?,?)",
            repoId, path, specHash,
        )
        VersionRecord(id, repoId, path, specHash, "draft")
    }

    fun listVersions(repoId: Long): List<VersionRecord> = db.transaction { conn ->
        conn.select(
            "SELECT * FROM version WHERE repo_id=? ORDER BY id",
            repoId,
        ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
    }

    // ── api（归一化接口投影）─────────────────────────────────────────────

    fun replaceApisForRepo(repoId: Long, apis: List<ApiRecord>) = db.transaction { conn ->
        conn.exec("DELETE FROM api WHERE repo_id=?", repoId)
        for (a in apis) {
            conn.insert(
                "INSERT OR REPLACE INTO api (repo_id, version_id, method, path, operation_id, tag, summary, spec_json) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
                repoId, a.versionId, a.method, a.path, a.operationId, a.tag, a.summary, a.specJson,
            )
        }
    }

    fun countApis(repoId: Long): Int = db.transaction { conn ->
        (conn.queryObj("SELECT COUNT(*) AS c FROM api WHERE repo_id=?", repoId) as? Number)?.toLong()?.toInt() ?: 0
    }

    fun listApis(repoId: Long, method: String? = null, tag: String? = null, keyword: String? = null): List<ApiRecord> =
        db.transaction { conn ->
            val sql = buildString {
                append("SELECT * FROM api WHERE repo_id=?")
                if (method != null) append(" AND method=?")
                if (tag != null) append(" AND tag=?")
                if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
            }
            val params = buildList<Any?> {
                add(repoId)
                if (method != null) add(method)
                if (tag != null) add(tag)
                if (keyword != null) { val k = "%$keyword%"; add(k); add(k); add(k) }
            }
            conn.select(sql, *params.toTypedArray()) { r ->
                ApiRecord(
                    r.long("id"), r.long("repo_id"), r.longOrNull("version_id"),
                    r["method"] as String, r["path"] as String,
                    r["operation_id"] as? String, r["tag"] as? String, r["summary"] as? String,
                    r["spec_json"] as String,
                )
            }
        }

    fun listAllApis(keyword: String? = null, method: String? = null): List<ApiRecord> = db.transaction { conn ->
        val sql = buildString {
            append("SELECT * FROM api WHERE 1=1")
            if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
            if (method != null) append(" AND method=?")
        }
        val params = buildList {
            if (keyword != null) { val k = "%$keyword%"; add(k); add(k); add(k) }
            if (method != null) add(method)
        }
        conn.select(sql, *params.toTypedArray()) { r ->
            ApiRecord(
                r.long("id"), r.long("repo_id"), r.longOrNull("version_id"),
                r["method"] as String, r["path"] as String,
                r["operation_id"] as? String, r["tag"] as? String, r["summary"] as? String,
                r["spec_json"] as String,
            )
        }
    }

    // ── schema（组件投影）───────────────────────────────────────────────

    fun replaceSchemasForRepo(repoId: Long, schemas: List<SchemaRecord>) = db.transaction { conn ->
        conn.exec("DELETE FROM schema WHERE repo_id=?", repoId)
        for (s in schemas) {
            conn.insert(
                "INSERT OR REPLACE INTO schema (repo_id, version_id, name, spec_json) VALUES (?,?,?,?)",
                repoId, s.versionId, s.name, s.specJson,
            )
        }
    }

    fun getSchema(repoId: Long, name: String): SchemaRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM schema WHERE repo_id=? AND name=?", repoId, name) { r ->
            SchemaRecord(r.long("id"), r.long("repo_id"), r.longOrNull("version_id"), r["name"] as String, r["spec_json"] as String)
        }.firstOrNull()
    }

    // ── 内部 SQL 助手 ─────────────────────────────────────────────────────

    private fun insertProject(conn: Connection, name: String): Long {
        projectIdByName(conn, name)?.let { return it }
        return conn.insert("INSERT OR IGNORE INTO project (name) VALUES (?)", name)
    }

    private fun projectIdByName(conn: Connection, name: String): Long? =
        (conn.queryObj("SELECT id FROM project WHERE name=?", name) as? Number)?.toLong()

    private fun getRepoByUrl(conn: Connection, projectId: Long, url: String): RepoRecord? =
        conn.select("SELECT * FROM repo WHERE project_id=? AND url=?", projectId, url) { r ->
            RepoRecord(r.long("id"), r.long("project_id"), r["url"] as String, r["branch"] as String, r["sync_status"] as String, r["last_error"] as? String)
        }.firstOrNull()

    private fun getRepoById(conn: Connection, id: Long): RepoRecord? =
        conn.select("SELECT * FROM repo WHERE id=?", id) { r ->
            RepoRecord(r.long("id"), r.long("project_id"), r["url"] as String, r["branch"] as String, r["sync_status"] as String, r["last_error"] as? String)
        }.firstOrNull()

    private fun getVersion(conn: Connection, repoId: Long, path: String, hash: String): VersionRecord? =
        conn.select("SELECT * FROM version WHERE repo_id=? AND path=? AND spec_hash=?", repoId, path, hash) { r ->
            VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String)
        }.firstOrNull()
}

// ── 行值转换（sqlite-jdbc 的 INTEGER 列经 getObject 返回 Integer，需按 Number 转）──

private fun Map<String, Any?>.long(key: String): Long = (this[key] as Number).toLong()

private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

// ── JDBC 小助手（避免样板；统一显式 try/finally，绕开 kotlin use 泛型在嵌套推断的不确定性）──

private fun Connection.exec(sql: String, vararg params: Any?) {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
    } finally {
        st.close()
    }
}

private fun Connection.insert(sql: String, vararg params: Any?): Long {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeUpdate()
        return st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
    } finally {
        st.close()
    }
}

private fun Connection.queryObj(sql: String, vararg params: Any?): Any? {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        val rs = st.executeQuery()
        try {
            return if (rs.next()) rs.getObject(1) else null
        } finally {
            rs.close()
        }
    } finally {
        st.close()
    }
}

private inline fun <T> Connection.select(sql: String, vararg params: Any?, map: (Map<String, Any?>) -> T): List<T> {
    val st = this.prepareStatement(sql)
    try {
        params.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        val rs = st.executeQuery()
        try {
            val meta = rs.metaData
            val cols = meta.columnCount
            val result = mutableListOf<T>()
            while (rs.next()) {
                val row = HashMap<String, Any?>(cols)
                for (i in 1..cols) {
                    row[meta.getColumnLabel(i)] = rs.getObject(i)
                }
                result.add(map(row))
            }
            return result
        } finally {
            rs.close()
        }
    } finally {
        st.close()
    }
}