package io.spechub.spec.index

import java.sql.Connection

/*
 * ProjectionStore —— 可重建投影 DAO（Phase 1 逻辑拆分：与 PlatformStore 同库）。
 *
 * 归属：version（发布快照）/ api / schema / doc / FTS。
 * 纪律：
 * - 投影层是 disposable 的：`replaceXxxForRepo` 先清该仓投影再整批插入，随时可从 git 全量重建。
 * - 幂等键：version(repo_id,path,spec_hash)、api(repo_id,method,path,stage)、schema(repo_id,name,stage)。
 * - 不引用管理数据外键：repo_id / project_id 均为逻辑列（Phase 2 物理拆分时投影库不再有 repo/project 表）。
 * - 保持 kernel 零 Spring：不使用 @Transactional，事务边界由 IndexDb.transaction 显式控制。
 */
class ProjectionStore(
    private val db: IndexDb,
) {
    // ── version（发布快照）───────────────────────────────────────────────

    /** 同文档同 hash 幂等：已存在则返回既有行，否则新建 */
    fun upsertVersion(
        repoId: Long,
        path: String,
        specHash: String,
    ): VersionRecord =
        db.transaction { conn ->
            getVersion(conn, repoId, path, specHash)?.let { return@transaction it }
            val id =
                conn.insert(
                    "INSERT INTO version (repo_id, path, spec_hash) VALUES (?,?,?)",
                    repoId,
                    path,
                    specHash,
                )
            VersionRecord(id, repoId, path, specHash, "draft")
        }

    fun listVersions(repoId: Long): List<VersionRecord> =
        db.transaction { conn ->
            conn.select(
                "SELECT * FROM version WHERE repo_id=? ORDER BY id",
                repoId,
            ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
        }

    /** 某 spec 文件全部版本（最新在前），供版本历史 / 发布状态展示 */
    fun listVersionsOfPath(
        repoId: Long,
        path: String,
    ): List<VersionRecord> =
        db.transaction { conn ->
            conn.select(
                "SELECT * FROM version WHERE repo_id=? AND path=? ORDER BY id DESC",
                repoId,
                path,
            ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
        }

    /**
     * 发布：把某 spec 文件的最新版本打 release tag，同 path 其它版本降回 draft；
     * 并把当前 draft 投影复制一份为 release 快照（消费者默认读 release，draft 继续演进互不影响）。
     * @return 升级为 release 的版本；该 path 无任何版本时返回 null。
     */
    fun promotePath(
        repoId: Long,
        path: String,
        specHash: String? = null,
    ): VersionRecord? =
        db.transaction { conn ->
            // specHash 指定用于投影重建后按管理库 release 记录重放；null = 取最新 draft
            val latest =
                (
                    if (specHash == null) {
                        conn
                            .select(
                                "SELECT * FROM version WHERE repo_id=? AND path=? ORDER BY id DESC LIMIT 1",
                                repoId,
                                path,
                            ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
                            .firstOrNull()
                    } else {
                        conn
                            .select(
                                "SELECT * FROM version WHERE repo_id=? AND path=? AND spec_hash=? ORDER BY id DESC LIMIT 1",
                                repoId,
                                path,
                                specHash,
                            ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }
                            .firstOrNull()
                    }
                ) ?: return@transaction null

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
                """.trimIndent(),
                repoId,
                latest.id,
            )
            conn.exec(
                """
                INSERT INTO schema (repo_id, version_id, name, spec_json, stage)
                SELECT repo_id, version_id, name, spec_json, '${Stage.RELEASE}'
                FROM schema WHERE repo_id=? AND version_id=? AND stage='${Stage.DRAFT}'
                ON CONFLICT(repo_id, name, stage) DO UPDATE SET
                    version_id=excluded.version_id, spec_json=excluded.spec_json
                """.trimIndent(),
                repoId,
                latest.id,
            )
            VersionRecord(latest.id, repoId, path, latest.specHash, Stage.RELEASE)
        }

    /** 某 spec 文件最近一次摄取的 spec_hash（摄取变更检测：与本次比较，不同则触发订阅通知） */
    fun latestSpecHash(
        repoId: Long,
        path: String,
    ): String? =
        db.transaction { conn ->
            (conn.queryObj("SELECT spec_hash FROM version WHERE repo_id=? AND path=? ORDER BY id DESC LIMIT 1", repoId, path) as? String)
        }

    // ── api（归一化接口投影）─────────────────────────────────────────────

    fun getApiById(id: Long): ApiRecord? =
        db.transaction { conn ->
            conn
                .select(
                    """SELECT a.*, v.path AS spec_path FROM api a LEFT JOIN version v ON v.id = a.version_id WHERE a.id=?""",
                    id,
                ) { r ->
                    ApiRecord(
                        (r["id"] as Number).toLong(),
                        r.long("repo_id"),
                        r.longOrNull("version_id"),
                        r["method"] as String,
                        r["path"] as String,
                        r["operation_id"] as? String,
                        r["tag"] as? String,
                        r["summary"] as? String,
                        r["spec_json"] as String,
                        specPath = r["spec_path"] as? String,
                    )
                }.firstOrNull()
        }

    /** 只重建 draft 档（release 快照副本是 promote 时刻的冻结投影，摄取不碰） */
    fun replaceApisForRepo(
        repoId: Long,
        apis: List<ApiRecord>,
    ) = db.transaction { conn ->
        conn.exec("DELETE FROM api WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (a in apis) {
            conn.insert(
                "INSERT INTO api (repo_id, version_id, method, path, operation_id, tag, summary, spec_json, stage) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                repoId,
                a.versionId,
                a.method,
                a.path,
                a.operationId,
                a.tag,
                a.summary,
                a.specJson,
                a.stage.ifBlank { Stage.DRAFT },
            )
        }
    }

    fun countApis(repoId: Long): Int =
        db.transaction { conn ->
            (conn.queryObj("SELECT COUNT(*) AS c FROM api WHERE repo_id=?", repoId) as? Number)?.toLong()?.toInt() ?: 0
        }

    fun listApis(
        repoId: Long,
        method: String? = null,
        tag: String? = null,
        keyword: String? = null,
        stage: String = Stage.DRAFT,
    ): List<ApiRecord> =
        db.transaction { conn ->
            val sql =
                buildString {
                    append("SELECT * FROM api WHERE repo_id=? AND stage=?")
                    if (method != null) append(" AND method=?")
                    if (tag != null) append(" AND tag=?")
                    if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
                }
            val params =
                buildList<Any?> {
                    add(repoId)
                    add(stage)
                    if (method != null) add(method)
                    if (tag != null) add(tag)
                    if (keyword != null) {
                        val k = "%$keyword%"
                        add(k)
                        add(k)
                        add(k)
                    }
                }
            conn.select(sql, *params.toTypedArray()) { r ->
                ApiRecord(
                    r.long("id"),
                    r.long("repo_id"),
                    r.longOrNull("version_id"),
                    r["method"] as String,
                    r["path"] as String,
                    r["operation_id"] as? String,
                    r["tag"] as? String,
                    r["summary"] as? String,
                    r["spec_json"] as String,
                    stage = r["stage"] as? String ?: Stage.DRAFT,
                )
            }
        }

    fun listAllApis(
        keyword: String? = null,
        method: String? = null,
        stage: String = Stage.DRAFT,
    ): List<ApiRecord> =
        db.transaction { conn ->
            val sql =
                buildString {
                    append("SELECT * FROM api WHERE stage=?")
                    if (keyword != null) append(" AND (path LIKE ? OR operation_id LIKE ? OR summary LIKE ?)")
                    if (method != null) append(" AND method=?")
                }
            val params =
                buildList<Any?> {
                    add(stage)
                    if (keyword != null) {
                        val k = "%$keyword%"
                        add(k)
                        add(k)
                        add(k)
                    }
                    if (method != null) add(method)
                }
            conn.select(sql, *params.toTypedArray()) { r ->
                ApiRecord(
                    r.long("id"),
                    r.long("repo_id"),
                    r.longOrNull("version_id"),
                    r["method"] as String,
                    r["path"] as String,
                    r["operation_id"] as? String,
                    r["tag"] as? String,
                    r["summary"] as? String,
                    r["spec_json"] as String,
                    stage = r["stage"] as? String ?: Stage.DRAFT,
                )
            }
        }

    /**
     * 一次摄取的全量投影替换（apis / schemas / docs 单事务）。
     * 复合原语：避免「api 已更新、schema/docs 未更新」的中间态被读到；重试仍幂等。
     * version 由 [upsertVersion] 幂等维护，不在此事务内（可先于本方法完成）。
     */
    fun replaceProjectionForRepo(
        repoId: Long,
        apis: List<ApiRecord>,
        schemas: List<SchemaRecord>,
        docs: List<DocRecord>,
    ) = db.transaction { conn ->
        conn.exec("DELETE FROM api WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (a in apis) {
            conn.insert(
                "INSERT INTO api (repo_id, version_id, method, path, operation_id, tag, summary, spec_json, stage) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                repoId,
                a.versionId,
                a.method,
                a.path,
                a.operationId,
                a.tag,
                a.summary,
                a.specJson,
                a.stage.ifBlank { Stage.DRAFT },
            )
        }

        conn.exec("DELETE FROM schema WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (s in schemas) {
            conn.insert(
                "INSERT INTO schema (repo_id, version_id, name, spec_json, stage) VALUES (?,?,?,?,?)",
                repoId,
                s.versionId,
                s.name,
                s.specJson,
                s.stage.ifBlank { Stage.DRAFT },
            )
        }

        val oldDocIds = conn.select("SELECT id FROM doc WHERE repo_id=?", repoId) { r -> r.long("id") }
        conn.exec("DELETE FROM doc WHERE repo_id=?", repoId)
        if (db.fts5Available) {
            for (id in oldDocIds) conn.exec("DELETE FROM doc_fts WHERE rowid=?", id)
        }
        for (d in docs) {
            val id =
                conn.insert(
                    "INSERT INTO doc (repo_id, version_id, path, title, markdown) VALUES (?,?,?,?,?)",
                    repoId,
                    d.versionId,
                    d.path,
                    d.title,
                    d.markdown,
                )
            if (db.fts5Available) {
                try {
                    conn.exec("INSERT INTO doc_fts (rowid, title, body) VALUES (?,?,?)", id, d.title, d.markdown)
                } catch (e: Exception) {
                    // FTS 写入失败不影响主数据
                }
            }
        }
    }

    // ── schema（组件投影）───────────────────────────────────────────────

    fun replaceSchemasForRepo(
        repoId: Long,
        schemas: List<SchemaRecord>,
    ) = db.transaction { conn ->
        conn.exec("DELETE FROM schema WHERE repo_id=? AND stage=?", repoId, Stage.DRAFT)
        for (s in schemas) {
            conn.insert(
                "INSERT INTO schema (repo_id, version_id, name, spec_json, stage) VALUES (?,?,?,?,?)",
                repoId,
                s.versionId,
                s.name,
                s.specJson,
                s.stage.ifBlank { Stage.DRAFT },
            )
        }
    }

    fun getSchema(
        repoId: Long,
        name: String,
    ): SchemaRecord? =
        db.transaction { conn ->
            conn
                .select("SELECT * FROM schema WHERE repo_id=? AND name=?", repoId, name) { r ->
                    SchemaRecord(r.long("id"), r.long("repo_id"), r.longOrNull("version_id"), r["name"] as String, r["spec_json"] as String)
                }.firstOrNull()
        }

    /** 某 repo 指定 stage 的全部 schema 投影（mock 生成时按 release/draft 取组件解析 $ref） */
    fun listSchemas(
        repoId: Long,
        stage: String = Stage.DRAFT,
    ): List<SchemaRecord> =
        db.transaction { conn ->
            conn.select("SELECT * FROM schema WHERE repo_id=? AND stage=?", repoId, stage) { r ->
                SchemaRecord(r.long("id"), r.long("repo_id"), r.longOrNull("version_id"), r["name"] as String, r["spec_json"] as String)
            }
        }

    // ── Doc 域（AI 文档层，两域隔离管理）──────────────────────────────────

    /** 摄取期全量替换某 repo 的文档投影（幂等；doc 表 UNIQUE(repo_id,path)） */
    fun replaceDocsForRepo(
        repoId: Long,
        docs: List<DocRecord>,
    ) = db.transaction { conn ->
        val oldIds = conn.select("SELECT id FROM doc WHERE repo_id=?", repoId) { r -> r.long("id") }
        conn.exec("DELETE FROM doc WHERE repo_id=?", repoId)
        if (db.fts5Available) {
            for (id in oldIds) conn.exec("DELETE FROM doc_fts WHERE rowid=?", id)
        }
        for (d in docs) {
            val id =
                conn.insert(
                    "INSERT INTO doc (repo_id, version_id, path, title, markdown) VALUES (?,?,?,?,?)",
                    repoId,
                    d.versionId,
                    d.path,
                    d.title,
                    d.markdown,
                )
            if (db.fts5Available) {
                try {
                    conn.exec("INSERT INTO doc_fts (rowid, title, body) VALUES (?,?,?)", id, d.title, d.markdown)
                } catch (e: Exception) {
                    // FTS 写入失败不影响主数据
                }
            }
        }
    }

    fun listDocs(repoId: Long? = null): List<DocRecord> =
        db.transaction { conn ->
            val sql = if (repoId != null) "SELECT * FROM doc WHERE repo_id=? ORDER BY path" else "SELECT * FROM doc ORDER BY path"
            conn.select(sql, *if (repoId != null) arrayOf(repoId) else emptyArray()) { rowToDoc(it) }
        }

    fun getDoc(id: Long): DocRecord? =
        db.transaction { conn ->
            conn.select<DocRecord>("SELECT * FROM doc WHERE id=?", id) { rowToDoc(it) }.firstOrNull()
        }

    /** 全文检索：FTS5 trigram 优先（中文子串），环境不支持回退 LIKE */
    fun searchDocs(
        keyword: String,
        repoId: Long? = null,
    ): List<DocRecord> =
        db.transaction { conn ->
            val kw = keyword.trim()
            if (kw.isEmpty()) return@transaction emptyList()
            val ftsHits =
                if (db.fts5Available) {
                    try {
                        // FTS5 MATCH：构造带引号的短语查询（防注入 + 按子串）
                        val q = "\"" + kw.replace("\"", "\"\"") + "\""
                        val sql =
                            buildString {
                                append("SELECT d.* FROM doc d JOIN doc_fts f ON f.rowid = d.id WHERE doc_fts MATCH ?")
                                if (repoId != null) append(" AND d.repo_id=?")
                            }
                        val params: Array<Any?> = if (repoId != null) arrayOf(q, repoId) else arrayOf(q)
                        conn.select<DocRecord>(sql, *params) { rowToDoc(it) }
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }

            // LIKE 兜底/合并（中文子串匹配；FTS5 未命中或不支持时覆盖）
            val likeParams: Array<Any?> = if (repoId != null) arrayOf("%$kw%", "%$kw%", repoId) else arrayOf("%$kw%", "%$kw%")
            val likeHits =
                conn.select<DocRecord>(
                    "SELECT * FROM doc WHERE (title LIKE ? OR markdown LIKE ?)" + (if (repoId != null) " AND repo_id=?" else ""),
                    *likeParams,
                ) { rowToDoc(it) }
            (ftsHits + likeHits).distinctBy { it.id }.toList()
        }

    // ── 内部 SQL 助手 ─────────────────────────────────────────────────────

    private fun rowToDoc(r: Map<String, Any?>): DocRecord =
        DocRecord(
            id = r.long("id"),
            repoId = r.long("repo_id"),
            versionId = r.longOrNull("version_id"),
            path = r["path"] as String,
            title = r["title"] as String,
            markdown = r["markdown"] as String,
            createdAt = r["created_at"] as? String,
        )

    private fun getVersion(
        conn: Connection,
        repoId: Long,
        path: String,
        hash: String,
    ): VersionRecord? =
        conn
            .select("SELECT * FROM version WHERE repo_id=? AND path=? AND spec_hash=?", repoId, path, hash) { r ->
                VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String)
            }.firstOrNull()
}
