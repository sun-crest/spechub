package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.CommentService
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.CommentRecord
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Role
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * I5 —— 轻评论（挂在接口 / 仓库上）。
 * - GET  /api/apis/{id}/comments   接口评论列表（时间正序）
 * - POST /api/apis/{id}/comments   {body}：发表评论（需登录；未启用鉴权放行）
 * - DELETE /api/comments/{id}      删除：作者本人 或 该仓 project admin+（平台 admin 恒可）
 * 视为协作轻量能力：只需「已登录用户」，不必 editor+（编辑权限留给写 spec 类）。
 */
@RestController
@RequestMapping("/api")
class CommentController(
    private val projection: ProjectionStore,
    private val changelogService: ChangelogService,
    private val repoService: RepoService,
    private val commentService: CommentService,
    private val auth: AuthService,
) {
    data class CommentBody(
        @field:NotBlank val body: String,
    )

    data class CommentView(
        val id: Long,
        val apiId: Long?,
        val repoId: Long,
        val method: String,
        val path: String,
        val author: String,
        val body: String,
        val createdAt: String?,
    )

    @GetMapping("/apis/{id}/comments")
    fun comments(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): List<CommentView> {
        val api = projection.getApiById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "api $id 不存在")
        auth.requireRepoMember(request, api.repoId, "查看评论")
        return commentService.listForEndpoint(api.repoId, api.method, api.path).map(::toView)
    }

    @PostMapping("/apis/{id}/comments")
    fun add(
        @PathVariable id: Long,
        @RequestBody @Valid body: CommentBody,
        request: HttpServletRequest,
    ): CommentView {
        val api = projection.getApiById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "api $id 不存在")
        val user = auth.requireRepoMember(request, api.repoId, "发表评论")
        val comment = commentService.add(api.repoId, api.id, null, api.method, api.path, user.username, body.body.trim())
        changelogService.add(api.repoId, api.id, user.username, "comment", "评论 ${api.method} ${api.path}：${body.body.trim().take(60)}")
        return toView(comment)
    }

    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        val actor = auth.requireAuthenticated(request, "删除评论")
        val comment = commentService.get(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "评论 $id 不存在")
        val projectId = repoService.getRepo(comment.repoId)?.projectId
        val role = auth.effectiveRole(actor, projectId)
        val isAuthor = actor.username == comment.author
        val isManager = Role.rank(role) >= Role.rank(Role.ADMIN)
        if (!isAuthor && !isManager && !actor.isPlatformAdmin) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "只能删除自己的评论或需 admin 及以上")
        }
        commentService.delete(id)
        changelogService.add(comment.repoId, comment.apiId, actor.username, "comment.delete", "删除评论 #${comment.id}")
    }

    private fun toView(c: CommentRecord): CommentView =
        CommentView(
            id = c.id!!,
            apiId = c.apiId,
            repoId = c.repoId,
            method = c.method,
            path = c.path,
            author = c.author,
            body = c.body,
            createdAt = c.createdAt,
        )
}
