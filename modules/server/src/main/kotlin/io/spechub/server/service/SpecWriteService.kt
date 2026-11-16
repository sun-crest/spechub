package io.spechub.server.service

import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.ReviewRecord
import io.spechub.spec.index.ReviewStatus
import io.spechub.spec.index.Role
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.UserRecord
import io.spechub.server.ingest.Ingestor
import io.spechub.server.web.AuthService
import io.spechub.server.web.SpecWriter
import io.spechub.server.web.UrlTokenStore
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import org.springframework.stereotype.Service

/*
 * R5 —— Spec 写回编排（规范 §1.2）：把散落在 Controller 的 git/DB 多步编排收口。
 *
 * 写路径承诺（§5 / 决策 1 落法 b）：
 * - 直写：writer.writeSpec（git commit+push）→ ingestor.ingest（索引重建）；失败即停，
 *   重试依赖 git/specHash 幂等（同内容续跑不重复建版本）。
 * - 启鉴权：先落评审（index.createReview + changelog），approve 才写真源；
 *   approve 的次序固定为「git 写 → 摄取 → resolveReview → changelog」，中途失败由调用方重试，
 *   评审状态不会先于真源落定。
 *
 * 鉴权决策复用：是否走评审只在 writeOrQueue 一处判定；approve/reject 的角色与
 * 「评审人须他人」规则也在本类，Controller 只负责取当前用户与 HTTP 状态码。
 */
@Service
class SpecWriteService(
    private val index: SpecIndexStore,
    private val writer: SpecWriter,
    private val ingestor: Ingestor,
    private val urlTokens: UrlTokenStore,
    private val auth: AuthService,
) {

    /**
     * 写回入口：未启用鉴权 → 直写 + 摄取；启用鉴权 → 入评审队列（不碰真源）。
     * [actor] 为当前操作者用户名（匿名/未启用时可为 anonymous）。
     */
    fun writeOrQueue(repo: RepoRecord, path: String, content: String, actor: String): WriteOutcome {
        // 未启用鉴权：本地/最小部署直写（保存即提交，现状；评审流只在团队启用鉴权后生效）
        if (!auth.authEnabled) {
            val written = writer.writeSpec(repo, path, content)
            val ing = ingestor.ingest(repo, urlTokens.get(repo.id))
            return WriteOutcome(
                repoId = repo.id, path = path, commit = written.commit, branch = written.branch,
                apiCount = ing.apiCount, message = "已提交 ${written.commit.take(8)}",
            )
        }

        // 启鉴权 →「写操作皆审」（§6.4 决策 3）：先入评审队列，approve 后才落真源
        val review = index.createReview(
            repoId = repo.id, path = path, newContent = content,
            diff = "", oldHash = index.latestSpecHash(repo.id, path), requester = actor,
        )
        index.addChangelog(
            repoId = repo.id, apiId = null, actor = actor, action = "review.create",
            detail = "提交评审 #${review.id}：$path（$actor 提交，等待审批）",
        )
        return WriteOutcome(
            repoId = repo.id, path = path, commit = "", branch = repo.branch, apiCount = 0,
            reviewId = review.id, status = ReviewStatus.PENDING,
            message = "已提交评审请求 #${review.id}：$path（写操作皆审，审批通过后落真源）",
        )
    }

    /**
     * 批准评审：git 写回 → 摄取 → resolveReview → changelog（固定次序，失败即停）。
     * @return 更新后的评审记录（status=approved）。
     */
    fun approve(reviewId: Long, actor: UserRecord?): ReviewRecord {
        val review = requirePending(reviewId)
        val reviewer = requireReviewer(actor, review.repoId, "审批评审")
        requireOtherReviewer(reviewer.username, review.requester, reviewer.isPlatformAdmin)

        val repo = index.getRepo(review.repoId)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo ${review.repoId} 不存在")
        val written = writer.writeSpec(repo, review.path, review.newContent)
        val ing = ingestor.ingest(repo, urlTokens.get(repo.id))
        index.resolveReview(reviewId, ReviewStatus.APPROVED, reviewer.username)
        index.addChangelog(
            repoId = review.repoId, apiId = null, actor = reviewer.username, action = "review.approve",
            detail = "批准评审 #$reviewId：${review.path} → commit ${written.commit.take(8)}（${ing.apiCount} 个接口）",
        )
        return index.getReview(reviewId)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评审 $reviewId 不存在")
    }

    /** 打回评审：只置状态与理由，真源不动。 */
    fun reject(reviewId: Long, reason: String?, actor: UserRecord?) {
        val review = requirePending(reviewId)
        val reviewer = requireReviewer(actor, review.repoId, "打回评审")
        requireOtherReviewer(reviewer.username, review.requester, reviewer.isPlatformAdmin)
        index.resolveReview(reviewId, ReviewStatus.REJECTED, reviewer.username, reason)
        index.addChangelog(
            repoId = review.repoId, apiId = null, actor = reviewer.username, action = "review.reject",
            detail = "打回评审 #$reviewId：${review.path}${reason?.let { "（$it）" } ?: ""}",
        )
    }

    /**
     * 导入写回：写入选定真源仓（git commit+push）→ 摄取；路径未指定时按导入类型生成默认路径。
     * 业务路径规则（.yaml/.yml 才采信、默认 `specs/import/<type>-<timestamp>.yaml`）在此收口。
     */
    fun importWrite(repoId: Long, requestedPath: String?, type: String, content: String): ImportWritten {
        val repo = index.getRepo(repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        val path = requestedPath?.takeIf { it.endsWith(".yaml") || it.endsWith(".yml") }
            ?: "specs/import/$type-${System.currentTimeMillis()}.yaml"
        val written = writer.writeSpec(repo, path, content)
        ingestor.ingest(repo, urlTokens.get(repoId))
        return ImportWritten(repoId, path, written.commit.take(12), written.branch)
    }

    /** 注册仓库并立即摄取（token 仅内存注入，不落库）；同步返回统计。 */
    fun registerRepo(projectName: String, url: String, branch: String?, token: String?): RepoRegistered {
        val repo = index.insertRepo(projectName, url.trim(), branch?.ifEmpty { null } ?: "main")
        if (token != null && token.isNotBlank()) {
            urlTokens.putWithToken(repo.id, url, token)
        }
        val ing = ingestor.ingest(repo, urlTokens.get(repo.id))
        return RepoRegistered(repo, ing)
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun requirePending(id: Long): ReviewRecord {
        val review = index.getReview(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评审 $id 不存在")
        if (review.status != ReviewStatus.PENDING) {
            throw ApiException(ApiErrorCode.CONFLICT, "评审 $id 已处理（${review.status}）")
        }
        return review
    }

    /**
     * 审批人：必须登录；启用鉴权时还需目标 repo editor+。
     * 未启用鉴权时保持既有语义——评审流本不应存在，但仍要求调用方带用户（否则 401）。
     */
    private fun requireReviewer(actor: UserRecord?, repoId: Long, op: String): UserRecord {
        val reviewer = actor ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        auth.requireRole(reviewer, repoId, Role.EDITOR, op)
        return reviewer
    }

    /** 评审人必须他人：requester 本人不可批/打回自己的提交（平台 admin 可自批排障） */
    private fun requireOtherReviewer(actor: String, requester: String, isAdmin: Boolean) {
        if (actor == requester && !isAdmin) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "需其他成员评审（不能批自己的提交）")
        }
    }

    /** 写回结果（Controller 转为 WriteSpecResponse） */
    data class WriteOutcome(
        val repoId: Long,
        val path: String,
        val commit: String,
        val branch: String,
        val apiCount: Int,
        val reviewId: Long? = null,
        val status: String? = null,
        val message: String = "",
    )

    /** 导入写回摘要（Controller 转为 WrittenInfo） */
    data class ImportWritten(val repoId: Long, val path: String, val commit: String, val branch: String)

    /** 注册 + 摄取结果（Controller 转为 RegisterResponse） */
    data class RepoRegistered(val repo: RepoRecord, val ingest: Ingestor.IngestResult)
}
