package io.spechub.spec.index

/*
 * PlatformStore —— 尚未迁入管理库的管理域 DAO（Phase 2 过渡：仍与投影同库 SQLite）。
 *
 * 归属：changelog / mock_rule / comment / subscription / notification / review。
 * 已迁出：project / repo（server `RepoService`）、user / membership（server `UserService`）。
 * 纪律：
 * - 管理数据**不得**外键指向投影表；引用内容一律用稳定键（repo_id + path / method + path）。
 * - 本类方法后续由 server 侧 Spring Data JDBC + @Transactional 逐域取代。
 * - 与 ProjectionStore 不共享事务：跨域多步写由调用方按固定次序 + 幂等编排（选型文档 §4）。
 */
class PlatformStore(private val db: IndexDb) {

    // ── changelog（变更记录：spec 写回 / 发布 / mock 规则变更都留痕）──────────

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
}
