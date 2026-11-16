package io.spechub.server.web

import io.spechub.spec.index.ReviewRecord
import io.spechub.spec.index.ReviewStatus
import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.service.SpecWriteService
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
 *
 * R5：审批/打回的「git 写 → 摄取 → resolveReview → changelog」编排、
 * 「评审人须他人」规则与待审状态校验全部下沉至 `SpecWriteService`；
 * 本类只保留 HTTP 映射、当前用户提取与 DTO 转换。
 */
@RestController
@RequestMapping("/api/reviews")
class ReviewController(
    private val index: SpecIndexStore,
    private val auth: AuthService,
    private val writer: SpecWriter,
    private val specWriteService: SpecWriteService,
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
        return index.listReviews(repoId, status).map(::toView)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): ReviewDetail {
        val r = index.getReview(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评审 $id 不存在")
        val current = index.getRepo(r.repoId)?.let { repo ->
            try { writer.readSpec(repo, r.path) } catch (e: Exception) { null }
        }
        return ReviewDetail(
            id = checkNotNull(r.id) { "评审记录缺 id" }, repoId = r.repoId, path = r.path, status = r.status, requester = r.requester,
            newContent = r.newContent, currentContent = current, oldHash = r.oldHash,
            reviewer = r.reviewer, rejectReason = r.rejectReason, createdAt = r.createdAt,
        )
    }

    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: Long, request: HttpServletRequest): ReviewView =
        toView(specWriteService.approve(id, auth.currentUser(request)))

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@PathVariable id: Long, request: HttpServletRequest, @RequestBody(required = false) body: RejectRequest?) {
        specWriteService.reject(id, body?.reason, auth.currentUser(request))
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun toView(r: ReviewRecord): ReviewView = ReviewView(
        id = checkNotNull(r.id) { "评审记录缺 id" }, repoId = r.repoId, path = r.path, status = r.status, requester = r.requester,
        reviewer = r.reviewer, rejectReason = r.rejectReason, createdAt = r.createdAt, resolvedAt = r.resolvedAt,
    )
}
