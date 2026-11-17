package io.spechub.spec.index

import java.sql.Connection

/*
 * PlatformStore —— 管理数据 DAO（Phase 1 逻辑拆分：与 ProjectionStore 同库，Phase 2 物理迁入管理库）。
 *
 * 归属：project / repo / changelog / mock_rule / user / membership / subscription / notification / review。
 * 纪律：
 * - 管理数据**不得**外键指向投影表；引用内容一律用稳定键（repo_id + path / method + path）。
 * - 本类方法在 Phase 2 会由 server 侧 Spring Data JDBC + @Transactional 取代；
 *   当前仍是 kernel raw JDBC，每个方法一个独立事务。
 * - 与 ProjectionStore 不共享事务：跨域多步写由调用方按固定次序 + 幂等编排（选型文档 §4）。
 */
class PlatformStore(private val db: IndexDb) {

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

    // ── mock_rule（规则引擎的存储）────────────────────────────────────────────

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

    fun getMockRule(id: Long): MockRuleRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM mock_rule WHERE id=?", id) { r ->
            MockRuleRecord(
                r.long("id"), r.long("repo_id"), r.longOrNull("api_id"),
                r["field_pattern"] as String, r["rule_type"] as String, r["rule_value"] as String,
            )
        }.firstOrNull()
    }

    // ── subscription / notification（订阅与变更通知）────────────────────────

    fun upsertSubscription(repoId: Long, apiId: Long?, method: String, path: String, team: String, notifyUrl: String = ""): SubscriptionRecord = db.transaction { conn ->
        val existing = conn.select(
            "SELECT id FROM subscription WHERE repo_id=? AND method=? AND path=? AND team=?",
            repoId, method, path, team,
        ) { r -> r.long("id") }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE subscription SET notify_url=?, api_id=? WHERE id=?", notifyUrl, apiId, existing)
            conn.select<SubscriptionRecord>("SELECT * FROM subscription WHERE id=?", existing) { rowToSubscription(it) }.firstOrNull()
                ?: SubscriptionRecord(existing, repoId, apiId, method, path, team, notifyUrl)
        } else {
            val id = conn.insert(
                "INSERT INTO subscription (repo_id, api_id, method, path, team, notify_url) VALUES (?,?,?,?,?,?)",
                repoId, apiId, method, path, team, notifyUrl,
            )
            SubscriptionRecord(id, repoId, apiId, method, path, team, notifyUrl)
        }
    }

    fun listSubscriptions(repoId: Long? = null): List<SubscriptionRecord> = db.transaction { conn ->
        val sql = if (repoId != null) "SELECT * FROM subscription WHERE repo_id=? ORDER BY team, path" else "SELECT * FROM subscription ORDER BY team, path"
        conn.select(sql, *if (repoId != null) arrayOf(repoId) else emptyArray()) { rowToSubscription(it) }
    }

    fun getSubscription(id: Long): SubscriptionRecord? = db.transaction { conn ->
        conn.select<SubscriptionRecord>("SELECT * FROM subscription WHERE id=?", id) { rowToSubscription(it) }.firstOrNull()
    }

    /** 摄取期变更检测：某接口（repo+method+path）的订阅方 */
    fun listSubscriptionsForEndpoint(repoId: Long, method: String, path: String): List<SubscriptionRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM subscription WHERE repo_id=? AND method=? AND path=? ORDER BY team", repoId, method, path) { rowToSubscription(it) }
    }

    fun deleteSubscription(id: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM subscription WHERE id=?", id)
    }

    fun addNotification(team: String, repoId: Long, method: String, path: String, summary: String): NotificationRecord = db.transaction { conn ->
        val id = conn.insert(
            "INSERT INTO notification (team, repo_id, method, path, summary) VALUES (?,?,?,?,?)",
            team, repoId, method, path, summary,
        )
        NotificationRecord(id, team, repoId, method, path, summary)
    }

    fun listNotifications(team: String? = null, limit: Int = 50): List<NotificationRecord> = db.transaction { conn ->
        val sql = if (team != null) {
            "SELECT * FROM notification WHERE team=? ORDER BY id DESC LIMIT ?"
        } else {
            "SELECT * FROM notification ORDER BY id DESC LIMIT ?"
        }
        val params: Array<Any?> = if (team != null) arrayOf(team, limit) else arrayOf(limit)
        conn.select(sql, *params) { r ->
            NotificationRecord(
                r.long("id"), r["team"] as String, r.long("repo_id"),
                r["method"] as String, r["path"] as String,
                r["summary"] as String, r["created_at"] as? String,
            )
        }
    }

    // ── review（评审审批流「写操作皆审」）─────────────────────────────────────

    fun createReview(repoId: Long, path: String, newContent: String, diff: String, oldHash: String?, requester: String): ReviewRecord = db.transaction { conn ->
        val id = conn.insert(
            "INSERT INTO review (repo_id, path, new_content, diff, old_hash, requester) VALUES (?,?,?,?,?,?)",
            repoId, path, newContent, diff, oldHash, requester,
        )
        ReviewRecord(id, repoId, path, newContent, diff, oldHash, ReviewStatus.PENDING, requester)
    }

    fun getReview(id: Long): ReviewRecord? = db.transaction { conn ->
        conn.select<ReviewRecord>("SELECT * FROM review WHERE id=?", id) { rowToReview(it) }.firstOrNull()
    }

    fun listReviews(repoId: Long? = null, status: String? = null): List<ReviewRecord> = db.transaction { conn ->
        val sql = buildString {
            append("SELECT * FROM review WHERE 1=1")
            if (repoId != null) append(" AND repo_id=?")
            if (status != null) append(" AND status=?")
            append(" ORDER BY id DESC")
        }
        val params: Array<Any?> = buildList {
            if (repoId != null) add(repoId)
            if (status != null) add(status)
        }.toTypedArray()
        conn.select(sql, *params) { rowToReview(it) }
    }

    fun resolveReview(id: Long, status: String, reviewer: String, rejectReason: String? = null) = db.transaction { conn ->
        conn.exec(
            "UPDATE review SET status=?, reviewer=?, reject_reason=?, resolved_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
            status, reviewer, rejectReason, id,
        )
    }

    // ── comment（轻评论）───────────────────────────────────────────────

    fun addComment(repoId: Long, apiId: Long?, docId: Long?, method: String, path: String, author: String, body: String): CommentRecord = db.transaction { conn ->
        val id = conn.insert(
            "INSERT INTO comment (repo_id, api_id, doc_id, method, path, author, body) VALUES (?,?,?,?,?,?,?)",
            repoId, apiId, docId, method, path, author, body,
        )
        CommentRecord(id, repoId, apiId, docId, method, path, author, body)
    }

    /** 按稳定寻址（repo + method + path）取评论：api 投影重建后依旧命中 */
    fun listCommentsForEndpoint(repoId: Long, method: String, path: String): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE repo_id=? AND method=? AND path=? ORDER BY id", repoId, method, path) { rowToComment(it) }
    }

    fun listCommentsForRepo(repoId: Long, limit: Int = 100): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE repo_id=? ORDER BY id DESC LIMIT ?", repoId, limit) { rowToComment(it) }
    }

    /** Doc 域评论（repo + path 稳定寻址：doc 投影重建后 id 会变，评论仍命中） */
    fun listCommentsForDoc(repoId: Long, path: String): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE repo_id=? AND path=? AND method='' ORDER BY id", repoId, path) { rowToComment(it) }
    }

    fun getComment(id: Long): CommentRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE id=?", id) { rowToComment(it) }.firstOrNull()
    }

    fun deleteComment(id: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM comment WHERE id=?", id)
    }

    // ── 内部 SQL 助手 ─────────────────────────────────────────────────────

    private fun rowToComment(r: Map<String, Any?>): CommentRecord = CommentRecord(
        id = r.long("id"),
        repoId = r.long("repo_id"),
        apiId = r.longOrNull("api_id"),
        docId = r.longOrNull("doc_id"),
        method = r["method"] as? String ?: "",
        path = r["path"] as? String ?: "",
        author = r["author"] as String,
        body = r["body"] as String,
        createdAt = r["created_at"] as? String,
    )

    private fun rowToSubscription(r: Map<String, Any?>): SubscriptionRecord = SubscriptionRecord(
        id = r.long("id"),
        repoId = r.long("repo_id"),
        apiId = r.longOrNull("api_id"),
        method = r["method"] as String,
        path = r["path"] as String,
        team = r["team"] as String,
        notifyUrl = r["notify_url"] as? String ?: "",
        createdAt = r["created_at"] as? String,
    )

    private fun rowToReview(r: Map<String, Any?>): ReviewRecord = ReviewRecord(
        id = r.long("id"),
        repoId = r.long("repo_id"),
        path = r["path"] as String,
        newContent = r["new_content"] as String,
        diff = r["diff"] as? String ?: "",
        oldHash = r["old_hash"] as? String,
        status = r["status"] as? String ?: ReviewStatus.PENDING,
        requester = r["requester"] as String,
        reviewer = r["reviewer"] as? String,
        rejectReason = r["reject_reason"] as? String,
        createdAt = r["created_at"] as? String,
        resolvedAt = r["resolved_at"] as? String,
    )

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
}
