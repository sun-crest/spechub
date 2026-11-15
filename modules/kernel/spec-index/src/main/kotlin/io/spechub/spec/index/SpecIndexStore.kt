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
        conn.select(
            """SELECT a.*, v.path AS spec_path FROM api a LEFT JOIN version v ON v.id = a.version_id WHERE a.id=?""",
            id,
        ) { r ->
            ApiRecord(
                (r["id"] as Number).toLong(), r.long("repo_id"), r.longOrNull("version_id"),
                r["method"] as String, r["path"] as String,
                r["operation_id"] as? String, r["tag"] as? String, r["summary"] as? String,
                r["spec_json"] as String,
                specPath = r["spec_path"] as? String,
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

    /** 某 spec 文件全部版本（最新在前），供版本历史 / 发布状态展示（I6） */
    fun listVersionsOfPath(repoId: Long, path: String): List<VersionRecord> = db.transaction { conn ->
        conn.select(
            "SELECT * FROM version WHERE repo_id=? AND path=? ORDER BY id DESC",
            repoId, path,
        ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
    }

    /**
     * 发布（I6）：把某 spec 文件的最新版本打 release tag，同 path 其它版本降回 draft；
     * 并把当前 draft 投影复制一份为 release 快照（§6.4 决策 5：消费者默认读 release，draft 继续演进互不影响）。
     * @return 升级为 release 的版本；该 path 无任何版本时返回 null。
     */
    fun promotePath(repoId: Long, path: String): VersionRecord? = db.transaction { conn ->
        val latest = conn.select(
            "SELECT * FROM version WHERE repo_id=? AND path=? ORDER BY id DESC LIMIT 1",
            repoId, path,
        ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }.firstOrNull()
            ?: return@transaction null

        // 同 path 其它版本降 draft（单一 release 语义：release 永远是「某个 spec 文件的最近一次发布」）
        conn.exec("UPDATE version SET stage=? WHERE repo_id=? AND path=? AND id!=?", Stage.DRAFT, repoId, path, latest.id)
        conn.exec("UPDATE version SET stage=? WHERE id=?", Stage.RELEASE, latest.id)

        // api / schema 投影复制 draft → release（ON CONFLICT DO UPDATE：同 key 幂等覆盖，id 不变）
        conn.exec(
            """
            INSERT INTO api (repo_id, version_id, method, path, operation_id, tag, summary, spec_json, stage)
            SELECT repo_id, version_id, method, path, operation_id, tag, summary, spec_json, '${Stage.RELEASE}'
            FROM api WHERE repo_id=? AND version_id=? AND stage='${Stage.DRAFT}'
            ON CONFLICT(repo_id, method, path, stage) DO UPDATE SET
                version_id=excluded.version_id, operation_id=excluded.operation_id, tag=excluded.tag,
                summary=excluded.summary, spec_json=excluded.spec_json
            """.trimIndent(), repoId, latest.id,
        )
        conn.exec(
            """
            INSERT INTO schema (repo_id, version_id, name, spec_json, stage)
            SELECT repo_id, version_id, name, spec_json, '${Stage.RELEASE}'
            FROM schema WHERE repo_id=? AND version_id=? AND stage='${Stage.DRAFT}'
            ON CONFLICT(repo_id, name, stage) DO UPDATE SET
                version_id=excluded.version_id, spec_json=excluded.spec_json
            """.trimIndent(), repoId, latest.id,
        )
        VersionRecord(latest.id, repoId, path, latest.specHash, Stage.RELEASE)
    }

    // ── changelog（变更记录，I5 铺底：spec 写回 / 发布 / mock 规则变更都留痕）──────────

    fun addChangelog(repoId: Long?, apiId: Long?, actor: String, action: String, detail: String) = db.transaction { conn ->
        conn.insert(
            "INSERT INTO changelog (repo_id, api_id, actor, action, detail) VALUES (?,?,?,?,?)",
            repoId, apiId, actor, action, detail,
        )
    }

    fun listChangelogs(repoId: Long?, apiId: Long?, limit: Int = 50): List<ChangelogRecord> = db.transaction { conn ->
        val sql = buildString {
            append("SELECT * FROM changelog WHERE 1=1")
            if (repoId != null) append(" AND repo_id=?")
            if (apiId != null) append(" AND api_id=?")
            append(" ORDER BY id DESC LIMIT ?")
        }
        val params = buildList<Any?> {
            if (repoId != null) add(repoId)
            if (apiId != null) add(apiId)
            add(limit)
        }
        conn.select(sql, *params.toTypedArray()) { r ->
            ChangelogRecord(
                r.long("id"), r.longOrNull("repo_id"), r.longOrNull("api_id"),
                r["actor"] as String, r["action"] as String, r["detail"] as String,
                r["created_at"] as? String,
            )
        }
    }

    // ── mock_rule（I2 规则引擎的存储）──────────────────────────────────────────

    fun upsertMockRule(rule: MockRuleRecord): MockRuleRecord = db.transaction { conn ->
        val existing = conn.select(
            "SELECT * FROM mock_rule WHERE repo_id=? AND api_id IS ? AND field_pattern=? AND rule_type=?",
            rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType,
        ) { r -> r.long("id") }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE mock_rule SET rule_value=? WHERE id=?", rule.ruleValue, existing)
            MockRuleRecord(existing, rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue)
        } else {
            val id = conn.insert(
                "INSERT INTO mock_rule (repo_id, api_id, field_pattern, rule_type, rule_value) VALUES (?,?,?,?,?)",
                rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue,
            )
            MockRuleRecord(id, rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue)
        }
    }

    fun listMockRules(repoId: Long): List<MockRuleRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM mock_rule WHERE repo_id=? ORDER BY id", repoId) { r ->
            MockRuleRecord(
                r.long("id"), r.long("repo_id"), r.longOrNull("api_id"),
                r["field_pattern"] as String, r["rule_type"] as String, r["rule_value"] as String,
            )
        }
    }

    fun deleteMockRule(id: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM mock_rule WHERE id=?", id)
    }

    // ── api（归一化接口投影）─────────────────────────────────────────────

    /** 只重建 draft 档（I6：release 快照副本是 promote 时刻的冻结投影，摄取代代不碰） */
    fun replaceApisForRepo(repoId: Long, apis: List<ApiRecord>) = db.transaction { conn ->
        conn.exec("DELETE FROM api WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (a in apis) {
            conn.insert(
                "INSERT INTO api (repo_id, version_id, method, path, operation_id, tag, summary, spec_json, stage) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                repoId, a.versionId, a.method, a.path, a.operationId, a.tag, a.summary, a.specJson,
                a.stage.ifBlank { Stage.DRAFT },
            )
        }
    }

    fun countApis(repoId: Long): Int = db.transaction { conn ->
        (conn.queryObj("SELECT COUNT(*) AS c FROM api WHERE repo_id=?", repoId) as? Number)?.toLong()?.toInt() ?: 0
    }

    fun listApis(repoId: Long, method: String? = null, tag: String? = null, keyword: String? = null, stage: String = Stage.DRAFT): List<ApiRecord> =
        db.transaction { conn ->
            val sql = buildString {
                append("SELECT * FROM api WHERE repo_id=? AND stage=?")
                if (method != null) append(" AND method=?")
                if (tag != null) append(" AND tag=?")
                if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
            }
            val params = buildList<Any?> {
                add(repoId); add(stage)
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
                    stage = r["stage"] as? String ?: Stage.DRAFT,
                )
            }
        }

    fun listAllApis(keyword: String? = null, method: String? = null, stage: String = Stage.DRAFT): List<ApiRecord> = db.transaction { conn ->
        val sql = buildString {
            append("SELECT * FROM api WHERE stage=?")
            if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
            if (method != null) append(" AND method=?")
        }
        val params = buildList<Any?> {
            add(stage)
            if (keyword != null) { val k = "%$keyword%"; add(k); add(k); add(k) }
            if (method != null) add(method)
        }
        conn.select(sql, *params.toTypedArray()) { r ->
            ApiRecord(
                r.long("id"), r.long("repo_id"), r.longOrNull("version_id"),
                r["method"] as String, r["path"] as String,
                r["operation_id"] as? String, r["tag"] as? String, r["summary"] as? String,
                r["spec_json"] as String,
                stage = r["stage"] as? String ?: Stage.DRAFT,
            )
        }
    }

    // ── schema（组件投影）───────────────────────────────────────────────

    fun replaceSchemasForRepo(repoId: Long, schemas: List<SchemaRecord>) = db.transaction { conn ->
        conn.exec("DELETE FROM schema WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (s in schemas) {
            conn.insert(
                "INSERT INTO schema (repo_id, version_id, name, spec_json, stage) VALUES (?,?,?,?,?)",
                repoId, s.versionId, s.name, s.specJson, s.stage.ifBlank { Stage.DRAFT },
            )
        }
    }

    fun getSchema(repoId: Long, name: String): SchemaRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM schema WHERE repo_id=? AND name=?", repoId, name) { r ->
            SchemaRecord(r.long("id"), r.long("repo_id"), r.longOrNull("version_id"), r["name"] as String, r["spec_json"] as String)
        }.firstOrNull()
    }

    /** 某 repo 指定 stage 的全部 schema 投影（mock 生成时按 release/draft 取组件解析 $ref） */
    fun listSchemas(repoId: Long, stage: String = Stage.DRAFT): List<SchemaRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM schema WHERE repo_id=? AND stage=?", repoId, stage) { r ->
            SchemaRecord(r.long("id"), r.long("repo_id"), r.longOrNull("version_id"), r["name"] as String, r["spec_json"] as String)
        }
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