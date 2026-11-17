package io.spechub.server.web

import io.spechub.spec.index.ReviewRecord
import io.spechub.spec.index.ReviewStatus
import io.spechub.spec.index.Role
import io.spechub.server.service.ChangelogService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.spechub.server.service.ReviewService
import io.spechub.server.service.RepoService

/*
 * I8 —— 评审审批流（§6.4 决策 3「写操作皆审」落地）：
 * - 启鉴权后，spec 写回（PUT /repos/{id}/spec）先进 pending 评审队列，不碰真源。
 * - 评审人 approve → SpecWriter 写回真源（平台 commit + push）→ 摄取重建索引。
 * - 评审人 reject（可带 reason）→ 状态弃置，真源不动。
 * - 评审人必须「他人」（requester 本人不可批自己的提交；平台 admin 可自批排障）。
 *
 * GET  /api/reviews?status=&repoId=   评审队列
 * GET  /api/reviews/{id}              详情（含当前真源内容 currentContent，供 diff 对比）
 * POST /api/reviews/{id}/approve      批准 → 落真源
 * POST /api/reviews/{id}/reject       {reason?} 打回
 */
@RestController
@RequestMapping("/api/reviews")
class ReviewController(
    private val changelogService: ChangelogService,
    private val repoService: RepoService,
    private val reviewService: ReviewService,
    private val auth: AuthService,
    private val writer: SpecWriter,
    private val ingestor: io.spechub.server.ingest.Ingestor,
    private val urlTokens: UrlTokenStore,
) {

    data class ReviewView(
        val id: Long,
        val repoId: Long,
        val path: String,
        val status: String,
        val requester: String,
        val reviewer: String? = null,
        val rejectReason: String? = null,
        val createdAt: String? = null,
        val resolvedAt: String? = null,
    )

    data class ReviewDetail(
        val id: Long,
        val repoId: Long,
        val path: String,
        val status: String,
        val requester: String,
        val newContent: String,
        /** 当前真源内容（diff 对比用）；读失败为 null */
        val currentContent: String? = null,
        val oldHash: String? = null,
        val reviewer: String? = null,
        val rejectReason: String? = null,
        val createdAt: String? = null,
    )

    data class RejectRequest(val reason: String? = null)

    @GetMapping
    fun list(@RequestParam status: String? = null, @RequestParam repoId: Long? = null): List<ReviewView> {
        if (status != null && !ReviewStatus.ALL.contains(status)) {
            throw ApiException(ApiErrorCode.VALIDATION, "status ∈ ${ReviewStatus.ALL}", listOf(ApiErrorBody.FieldError("status", "status ∈ ${ReviewStatus.ALL}")))
        }
        return reviewService.list(repoId, status).map(::toView)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): ReviewDetail {
        val r = reviewService.get(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评审 $id 不存在")
        val current = repoService.getRepo(r.repoId)?.let { repo ->
            try { writer.readSpec(repo, r.path) } catch (e: Exception) { null }
        }
        return ReviewDetail(
            id = r.id!!, repoId = r.repoId, path = r.path, status = r.status, requester = r.requester,
            newContent = r.newContent, currentContent = current, oldHash = r.oldHash,
            reviewer = r.reviewer, rejectReason = r.rejectReason, createdAt = r.createdAt,
        )
    }

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long, request: HttpServletRequest): ReviewView {
        val r = requirePending(id)
        auth.requireRepoRole(request, r.repoId, Role.EDITOR, "审批评审")
        val actor = auth.currentUser(request) ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "审批需要登录")
        requireOtherReviewer(actor.username, r.requester, actor.isPlatformAdmin)

        val repo = repoService.getRepo(r.repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo ${r.repoId} 不存在")
        val written = writer.writeSpec(repo, r.path, r.newContent)
        val ing = ingestor.ingest(repo, urlTokens.get(r.repoId))
        if (!reviewService.resolve(r.id!!, ReviewStatus.APPROVED, actor.username)) {
            throw ApiException(ApiErrorCode.CONFLICT, "评审已被处理，请刷新")
        }
        changelogService.add(r.repoId, null, actor.username, "review.approve",
            "批准评审 #${r.id}：${r.path} → commit ${written.commit.take(8)}（${ing.apiCount} 个接口）")
        return toView(reviewService.get(id)!!)
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@PathVariable id: Long, request: HttpServletRequest, @RequestBody(required = false) body: RejectRequest?) {
        val r = requirePending(id)
        auth.requireRepoRole(request, r.repoId, Role.EDITOR, "打回评审")
        val actor = auth.currentUser(request) ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "打回需要登录")
        requireOtherReviewer(actor.username, r.requester, actor.isPlatformAdmin)
        if (!reviewService.resolve(r.id!!, ReviewStatus.REJECTED, actor.username, body?.reason)) {
            throw ApiException(ApiErrorCode.CONFLICT, "评审已被处理，请刷新")
        }
        changelogService.add(r.repoId, null, actor.username, "review.reject",
            "打回评审 #${r.id}：${r.path}${body?.reason?.let { "（$it）" } ?: ""}")
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun requirePending(id: Long): ReviewRecord {
        val r = reviewService.get(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评审 $id 不存在")
        if (r.status != ReviewStatus.PENDING) {
            throw ApiException(ApiErrorCode.CONFLICT, "评审 $id 已处理（${r.status}）")
        }
        return r
    }

    /** 评审人必须他人：requester 本人不可批/打回自己的提交（平台 admin 可自批排障） */
    private fun requireOtherReviewer(actor: String, requester: String, isAdmin: Boolean) {
        if (actor == requester && !isAdmin) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "需其他成员评审（不能批自己的提交）")
        }
    }

    private fun toView(r: ReviewRecord): ReviewView = ReviewView(
        id = r.id!!, repoId = r.repoId, path = r.path, status = r.status, requester = r.requester,
        reviewer = r.reviewer, rejectReason = r.rejectReason, createdAt = r.createdAt, resolvedAt = r.resolvedAt,
    )
}