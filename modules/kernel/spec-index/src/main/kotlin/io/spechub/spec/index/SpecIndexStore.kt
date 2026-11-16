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
        promotePath(conn, repoId, path)
    }

    /**
     * R6 —— 发布 + changelog 同一事务：发布成功才留痕，任一失败一并回滚。
     * [detail] 在事务内按发布结果生成留痕文案（纯格式化，不做 I/O）。
     */
    fun promotePathWithChangelog(
        repoId: Long,
        path: String,
        actor: String,
        action: String,
        detail: (VersionRecord) -> String,
    ): VersionRecord? = db.transaction { conn ->
        val promoted = promotePath(conn, repoId, path) ?: return@transaction null
        conn.insertChangelog(repoId, null, actor, action, detail(promoted))
        promoted
    }

    private fun promotePath(conn: Connection, repoId: Long, path: String): VersionRecord? {
        val latest = conn.select(
            "SELECT * FROM version WHERE repo_id=? AND path=? ORDER BY id DESC LIMIT 1",
            repoId, path,
        ) { r -> VersionRecord(r.long("id"), r.long("repo_id"), r["path"] as String, r["spec_hash"] as String, r["stage"] as String) }.firstOrNull()
            ?: return null

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
        return VersionRecord(latest.id, repoId, path, latest.specHash, Stage.RELEASE)
    }

    // ── changelog（变更记录，I5 铺底：spec 写回 / 发布 / mock 规则变更都留痕）──────────

    fun addChangelog(repoId: Long?, apiId: Long?, actor: String, action: String, detail: String) = db.transaction { conn ->
        conn.insertChangelog(repoId, apiId, actor, action, detail)
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
        upsertMockRule(conn, rule)
    }

    /** R6 —— mock 规则 upsert + changelog 同一事务（写入成功才留痕）。 */
    fun upsertMockRuleWithChangelog(
        rule: MockRuleRecord,
        actor: String,
        action: String,
        detail: String,
    ): MockRuleRecord = db.transaction { conn ->
        val saved = upsertMockRule(conn, rule)
        conn.insertChangelog(saved.repoId, saved.apiId, actor, action, detail)
        saved
    }

    private fun upsertMockRule(conn: Connection, rule: MockRuleRecord): MockRuleRecord {
        val existing = conn.select(
            "SELECT * FROM mock_rule WHERE repo_id=? AND api_id IS ? AND field_pattern=? AND rule_type=?",
            rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType,
        ) { r -> r.long("id") }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE mock_rule SET rule_value=? WHERE id=?", rule.ruleValue, existing)
            return MockRuleRecord(existing, rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue)
        }
        val id = conn.insert(
            "INSERT INTO mock_rule (repo_id, api_id, field_pattern, rule_type, rule_value) VALUES (?,?,?,?,?)",
            rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue,
        )
        return MockRuleRecord(id, rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType, rule.ruleValue)
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

    // ── user / membership（I4 RBAC）────────────────────────────────────────────

    /** bootstrap 平台 admin（username=admin，token_hash=SHA-256(adminToken)），幂等 */
    fun ensurePlatformAdmin(tokenHash: String): UserRecord = db.transaction { conn ->
        val existing = conn.select("SELECT * FROM app_user WHERE username=?", USER_ADMIN_NAME) { rowToUser(it) }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE app_user SET token_hash=?, is_platform_admin=1 WHERE id=?", tokenHash, existing.id)
            existing.copy(tokenHash = tokenHash, isPlatformAdmin = true)
        } else {
            val id = conn.insert(
                "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?,?,?,1)",
                USER_ADMIN_NAME, USER_ADMIN_NAME, tokenHash,
            )
            UserRecord(id, USER_ADMIN_NAME, USER_ADMIN_NAME, tokenHash, isPlatformAdmin = true)
        }
    }

    /** 按 token hash 查用户（认证期）；未命中返回 null */
    fun findUserByTokenHash(tokenHash: String): UserRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM app_user WHERE token_hash=?", tokenHash) { rowToUser(it) }.firstOrNull()
    }

    fun findUserById(id: Long): UserRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM app_user WHERE id=?", id) { rowToUser(it) }.firstOrNull()
    }

    fun findByUsername(username: String): UserRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM app_user WHERE username=?", username) { rowToUser(it) }.firstOrNull()
    }

    /** 平台 admin（is_platform_admin=1 的任意用户组） */
    fun findPlatformAdmins(): List<UserRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM app_user WHERE is_platform_admin=1") { rowToUser(it) }
    }

    /** 创建用户并可随即加入某项目（普通用户）；返回落库用户 */
    fun createUser(username: String, displayName: String, tokenHash: String): UserRecord = db.transaction { conn ->
        val id = conn.insert(
            "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?,?,?,0)",
            username, displayName, tokenHash,
        )
        UserRecord(id, username, displayName, tokenHash)
    }

    /**
     * R6 —— 复合原子写：创建用户 + 加入项目成员在同一事务内完成。
     * 任一插入失败（如 projectId 不存在触发 FK）→ 整个事务回滚，避免留下「半用户」。
     */
    fun createUserWithMembership(
        username: String,
        displayName: String,
        tokenHash: String,
        projectId: Long?,
        role: String,
    ): UserRecord = db.transaction { conn ->
        createUserWithMembership(conn, username, displayName, tokenHash, projectId, role)
    }

    /** R6 —— 创建用户 + 成员 + changelog 同一事务（用户域写路径全原子）。 */
    fun createUserWithMembershipWithChangelog(
        username: String,
        displayName: String,
        tokenHash: String,
        projectId: Long?,
        role: String,
        actor: String,
        action: String,
        detail: String,
    ): UserRecord = db.transaction { conn ->
        val user = createUserWithMembership(conn, username, displayName, tokenHash, projectId, role)
        conn.insertChangelog(null, null, actor, action, detail)
        user
    }

    private fun createUserWithMembership(
        conn: Connection,
        username: String,
        displayName: String,
        tokenHash: String,
        projectId: Long?,
        role: String,
    ): UserRecord {
        val id = conn.insert(
            "INSERT INTO app_user (username, display_name, token_hash, is_platform_admin) VALUES (?,?,?,0)",
            username, displayName, tokenHash,
        )
        if (projectId != null) {
            conn.insert(
                "INSERT INTO membership (user_id, project_id, role) VALUES (?,?,?)",
                id, projectId, role,
            )
        }
        return UserRecord(id, username, displayName, tokenHash)
    }

    fun deleteUser(id: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM app_user WHERE id=?", id)
    }

    /** R6 —— 删除用户 + changelog 同一事务。 */
    fun deleteUserWithChangelog(id: Long, actor: String, action: String, detail: String) = db.transaction { conn ->
        conn.exec("DELETE FROM app_user WHERE id=?", id)
        conn.insertChangelog(null, null, actor, action, detail)
    }

    fun updateUserToken(id: Long, newHash: String) = db.transaction { conn ->
        conn.exec("UPDATE app_user SET token_hash=? WHERE id=?", newHash, id)
    }

    fun setMembership(userId: Long, projectId: Long, role: String) = db.transaction { conn ->
        setMembership(conn, userId, projectId, role)
    }

    /**
     * R6 —— 成员角色写 + changelog 同一事务（projectId 为空时仅留痕，兼容默认项目缺失场景）。
     * 角色写失败（如 FK 不满足）→ changelog 一并回滚。
     */
    fun setMembershipWithChangelog(
        userId: Long,
        projectId: Long?,
        role: String,
        actor: String,
        action: String,
        detail: String,
    ) = db.transaction { conn ->
        if (projectId != null) setMembership(conn, userId, projectId, role)
        conn.insertChangelog(null, null, actor, action, detail)
    }

    private fun setMembership(conn: Connection, userId: Long, projectId: Long, role: String) {
        val existing = conn.select(
            "SELECT id FROM membership WHERE user_id=? AND project_id=?",
            userId, projectId,
        ) { r -> r.long("id") }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE membership SET role=? WHERE id=?", role, existing)
        } else {
            conn.insert("INSERT INTO membership (user_id, project_id, role) VALUES (?,?,?)", userId, projectId, role)
        }
    }

    fun deleteMembership(userId: Long, projectId: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM membership WHERE user_id=? AND project_id=?", userId, projectId)
    }

    /** 用户在某项目上的角色；无成员关系返回 null（无权限） */
    fun membershipRole(userId: Long, projectId: Long): String? = db.transaction { conn ->
        (conn.queryObj("SELECT role FROM membership WHERE user_id=? AND project_id=?", userId, projectId) as? String)
    }

    /** 用户全部成员关系（projectId → role），成员管理 UI 用 */
    fun membershipsOf(userId: Long): Map<Long, String> = db.transaction { conn ->
        conn.select("SELECT project_id, role FROM membership WHERE user_id=?", userId) { r ->
            (r["project_id"] as Number).toInt().toLong() to (r["role"] as String)
        }.toMap()
    }

    /** 项目成员列表（userId → role），成员管理 UI 用 */
    fun membersOfProject(projectId: Long): Map<Long, String> = db.transaction { conn ->
        conn.select("SELECT user_id, role FROM membership WHERE project_id=?", projectId) { r ->
            (r["user_id"] as Number).toInt().toLong() to (r["role"] as String)
        }.toMap()
    }

    fun listUsers(): List<UserRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM app_user ORDER BY is_platform_admin DESC, id") { rowToUser(it) }
    }

    // ── subscription / notification（I7 订阅与变更通知）────────────────────────

    /** 某 spec 文件最近一次摄取的 spec_hash（变更检测：与本次比较，不同则订阅通知） */
    fun latestSpecHash(repoId: Long, path: String): String? = db.transaction { conn ->
        (conn.queryObj("SELECT spec_hash FROM version WHERE repo_id=? AND path=? ORDER BY id DESC LIMIT 1", repoId, path) as? String)
    }

    fun upsertSubscription(repoId: Long, apiId: Long?, method: String, path: String, team: String, notifyUrl: String = ""): SubscriptionRecord = db.transaction { conn ->
        upsertSubscription(conn, repoId, apiId, method, path, team, notifyUrl)
    }

    /** R6 —— 订阅 upsert + changelog 同一事务（订阅写成功才留痕）。 */
    fun upsertSubscriptionWithChangelog(
        repoId: Long,
        apiId: Long?,
        method: String,
        path: String,
        team: String,
        notifyUrl: String,
        actor: String,
        action: String,
        detail: String,
    ): SubscriptionRecord = db.transaction { conn ->
        val sub = upsertSubscription(conn, repoId, apiId, method, path, team, notifyUrl)
        conn.insertChangelog(sub.repoId, sub.apiId, actor, action, detail)
        sub
    }

    private fun upsertSubscription(
        conn: Connection,
        repoId: Long,
        apiId: Long?,
        method: String,
        path: String,
        team: String,
        notifyUrl: String,
    ): SubscriptionRecord {
        val existing = conn.select(
            "SELECT id FROM subscription WHERE repo_id=? AND method=? AND path=? AND team=?",
            repoId, method, path, team,
        ) { r -> r.long("id") }.firstOrNull()
        if (existing != null) {
            conn.exec("UPDATE subscription SET notify_url=?, api_id=? WHERE id=?", notifyUrl, apiId, existing)
            return conn.select<SubscriptionRecord>("SELECT * FROM subscription WHERE id=?", existing) { rowToSubscription(it) }.firstOrNull()
                ?: SubscriptionRecord(existing, repoId, apiId, method, path, team, notifyUrl)
        }
        val id = conn.insert(
            "INSERT INTO subscription (repo_id, api_id, method, path, team, notify_url) VALUES (?,?,?,?,?,?)",
            repoId, apiId, method, path, team, notifyUrl,
        )
        return SubscriptionRecord(id, repoId, apiId, method, path, team, notifyUrl)
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

    /** R6 —— 取消订阅 + changelog 同一事务。 */
    fun deleteSubscriptionWithChangelog(
        id: Long,
        repoId: Long,
        apiId: Long?,
        actor: String,
        action: String,
        detail: String,
    ) = db.transaction { conn ->
        conn.exec("DELETE FROM subscription WHERE id=?", id)
        conn.insertChangelog(repoId, apiId, actor, action, detail)
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

    // ── review（I8 评审审批流「写操作皆审」）───────────────────────────────────

    fun createReview(repoId: Long, path: String, newContent: String, diff: String, oldHash: String?, requester: String): ReviewRecord = db.transaction { conn ->
        createReview(conn, repoId, path, newContent, diff, oldHash, requester)
    }

    /**
     * R6 —— 提交评审 + changelog 同一事务；[detail] 按落库评审记录生成留痕文案。
     */
    fun createReviewWithChangelog(
        repoId: Long,
        path: String,
        newContent: String,
        diff: String,
        oldHash: String?,
        requester: String,
        actor: String,
        action: String,
        detail: (ReviewRecord) -> String,
    ): ReviewRecord = db.transaction { conn ->
        val review = createReview(conn, repoId, path, newContent, diff, oldHash, requester)
        conn.insertChangelog(repoId, null, actor, action, detail(review))
        review
    }

    private fun createReview(
        conn: Connection,
        repoId: Long,
        path: String,
        newContent: String,
        diff: String,
        oldHash: String?,
        requester: String,
    ): ReviewRecord {
        val id = conn.insert(
            "INSERT INTO review (repo_id, path, new_content, diff, old_hash, requester) VALUES (?,?,?,?,?,?)",
            repoId, path, newContent, diff, oldHash, requester,
        )
        return ReviewRecord(id, repoId, path, newContent, diff, oldHash, ReviewStatus.PENDING, requester)
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
        resolveReview(conn, id, status, reviewer, rejectReason)
    }

    /** R6 —— 评审落定 + changelog 同一事务（状态与审计同时生效/回滚）。 */
    fun resolveReviewWithChangelog(
        repoId: Long,
        id: Long,
        status: String,
        reviewer: String,
        rejectReason: String?,
        actor: String,
        action: String,
        detail: String,
    ) = db.transaction { conn ->
        resolveReview(conn, id, status, reviewer, rejectReason)
        conn.insertChangelog(repoId, null, actor, action, detail)
    }

    private fun resolveReview(conn: Connection, id: Long, status: String, reviewer: String, rejectReason: String?) {
        conn.exec(
            "UPDATE review SET status=?, reviewer=?, reject_reason=?, resolved_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
            status, reviewer, rejectReason, id,
        )
    }

    // ── Doc 域（K AI 文档层，两域隔离管理，§5.7）─────────────────────────────

    /** 摄取期全量替换某 repo 的文档投影（幂等；doc 表 UNIQUE(repo_id,path)） */
    fun replaceDocsForRepo(repoId: Long, docs: List<DocRecord>) = db.transaction { conn ->
        val oldIds = conn.select("SELECT id FROM doc WHERE repo_id=?", repoId) { r -> r.long("id") }
        conn.exec("DELETE FROM doc WHERE repo_id=?", repoId)
        if (db.fts5Available) {
            for (id in oldIds) conn.exec("DELETE FROM doc_fts WHERE rowid=?", id)
        }
        for (d in docs) {
            val id = conn.insert(
                "INSERT INTO doc (repo_id, version_id, path, title, markdown) VALUES (?,?,?,?,?)",
                repoId, d.versionId, d.path, d.title, d.markdown,
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

    fun listDocs(repoId: Long? = null): List<DocRecord> = db.transaction { conn ->
        val sql = if (repoId != null) "SELECT * FROM doc WHERE repo_id=? ORDER BY path" else "SELECT * FROM doc ORDER BY path"
        conn.select(sql, *if (repoId != null) arrayOf(repoId) else emptyArray()) { rowToDoc(it) }
    }

    fun getDoc(id: Long): DocRecord? = db.transaction { conn ->
        conn.select<DocRecord>("SELECT * FROM doc WHERE id=?", id) { rowToDoc(it) }.firstOrNull()
    }

    /** 全文检索（K2）：FTS5 trigram 优先（中文子串），环境不支持回退 LIKE */
    fun searchDocs(keyword: String, repoId: Long? = null): List<DocRecord> = db.transaction { conn ->
        val kw = keyword.trim()
        if (kw.isEmpty()) return@transaction emptyList()
        val ftsHits = if (db.fts5Available) {
            try {
                // FTS5 MATCH：构造带引号的短语查询（防注入 + 按子串）
                val q = "\"" + kw.replace("\"", "\"\"") + "\""
                val sql = buildString {
                    append("SELECT d.* FROM doc d JOIN doc_fts f ON f.rowid = d.id WHERE doc_fts MATCH ?")
                    if (repoId != null) append(" AND d.repo_id=?")
                }
                val params: Array<Any?> = if (repoId != null) arrayOf(q, repoId) else arrayOf(q)
                conn.select<DocRecord>(sql, *params) { rowToDoc(it) }
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        // LIKE 兜底/合并（中文子串匹配；FTS5 未命中或不支持时覆盖）
        val likeParams: Array<Any?> = if (repoId != null) arrayOf("%$kw%", "%$kw%", repoId) else arrayOf("%$kw%", "%$kw%")
        val likeHits = conn.select<DocRecord>(
            "SELECT * FROM doc WHERE (title LIKE ? OR markdown LIKE ?)" + (if (repoId != null) " AND repo_id=?" else ""),
            *likeParams,
        ) { rowToDoc(it) }
        (ftsHits + likeHits).distinctBy { it.id }.toList()
    }

    // ── comment（I5 轻评论）───────────────────────────────────────────────

    fun addComment(repoId: Long, apiId: Long?, docId: Long?, method: String, path: String, author: String, body: String): CommentRecord = db.transaction { conn ->
        addComment(conn, repoId, apiId, docId, method, path, author, body)
    }

    /** R6 —— 评论 + changelog 同一事务（api 评论与 doc 评论共用；成功才留痕）。 */
    fun addCommentWithChangelog(
        repoId: Long,
        apiId: Long?,
        docId: Long?,
        method: String,
        path: String,
        author: String,
        body: String,
        actor: String,
        action: String,
        detail: String,
    ): CommentRecord = db.transaction { conn ->
        val comment = addComment(conn, repoId, apiId, docId, method, path, author, body)
        conn.insertChangelog(comment.repoId, comment.apiId, actor, action, detail)
        comment
    }

    private fun addComment(
        conn: Connection,
        repoId: Long,
        apiId: Long?,
        docId: Long?,
        method: String,
        path: String,
        author: String,
        body: String,
    ): CommentRecord {
        val id = conn.insert(
            "INSERT INTO comment (repo_id, api_id, doc_id, method, path, author, body) VALUES (?,?,?,?,?,?,?)",
            repoId, apiId, docId, method, path, author, body,
        )
        return CommentRecord(id, repoId, apiId, docId, method, path, author, body)
    }

    /** 按稳定寻址（repo + method + path）取评论：api 投影重建后依旧命中 */
    fun listCommentsForEndpoint(repoId: Long, method: String, path: String): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE repo_id=? AND method=? AND path=? ORDER BY id", repoId, method, path) { rowToComment(it) }
    }

    fun listCommentsForRepo(repoId: Long, limit: Int = 100): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE repo_id=? ORDER BY id DESC LIMIT ?", repoId, limit) { rowToComment(it) }
    }

    /** I5/K：Doc 域评论（comment.doc_id 稳定寻址，不随 api 投影重建影响） */
    fun listCommentsForDoc(docId: Long): List<CommentRecord> = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE doc_id=? ORDER BY id", docId) { rowToComment(it) }
    }

    fun getComment(id: Long): CommentRecord? = db.transaction { conn ->
        conn.select("SELECT * FROM comment WHERE id=?", id) { rowToComment(it) }.firstOrNull()
    }

    fun deleteComment(id: Long) = db.transaction { conn ->
        conn.exec("DELETE FROM comment WHERE id=?", id)
    }

    /** R6 —— 删除评论 + changelog 同一事务（api/doc 评论共用）。 */
    fun deleteCommentWithChangelog(
        id: Long,
        repoId: Long,
        apiId: Long?,
        actor: String,
        action: String,
        detail: String,
    ) = db.transaction { conn ->
        conn.exec("DELETE FROM comment WHERE id=?", id)
        conn.insertChangelog(repoId, apiId, actor, action, detail)
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

    private fun rowToUser(r: Map<String, Any?>): UserRecord = UserRecord(
        id = r.long("id"),
        username = r["username"] as String,
        displayName = r["display_name"] as? String ?: "",
        tokenHash = r["token_hash"] as String,
        isPlatformAdmin = ((r["is_platform_admin"] as? Number)?.toInt() ?: 0) == 1,
        createdAt = r["created_at"] as? String,
    )

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

    private fun rowToDoc(r: Map<String, Any?>): DocRecord = DocRecord(
        id = r.long("id"),
        repoId = r.long("repo_id"),
        versionId = r.longOrNull("version_id"),
        path = r["path"] as String,
        title = r["title"] as String,
        markdown = r["markdown"] as String,
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

    private fun Connection.insertChangelog(repoId: Long?, apiId: Long?, actor: String, action: String, detail: String) {
        insert(
            "INSERT INTO changelog (repo_id, api_id, actor, action, detail) VALUES (?,?,?,?,?)",
            repoId, apiId, actor, action, detail,
        )
    }

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

    companion object {
        /** bootstrap 平台 admin 用户名（由 spechub.admin-token 支撑） */
        const val USER_ADMIN_NAME = "admin"
    }
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