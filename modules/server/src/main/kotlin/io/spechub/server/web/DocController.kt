package io.spechub.server.web

import io.spechub.spec.index.CommentRecord
import io.spechub.spec.index.PlatformStore
import io.spechub.spec.index.ProjectionStore
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/*
 * K1/K2/K3 —— Doc 域（AI 文档层，§5.7 两域隔离）：列表/详情/全文检索 + 轻评论。
 * - GET  /api/docs?repoId=&q=    Doc 列表（q 走 FTS5 trigram / LIKE 全文检索，K2）
 * - GET  /api/docs/{id}          详情（markdown 全文；content 含 front-matter 解析后的正文）
 * - GET/POST /api/docs/{id}/comments   Doc 评论（comment 用 repo_id + path 稳定寻址，投影重建不丢）
 * 渲染在 Web（react-markdown）；Doc↔Api 链接 = 正文「关联接口」节（front-matter related，K3）。
 */
@RestController
@RequestMapping("/api/docs")
class DocController(
    private val projection: ProjectionStore,
    private val platform: PlatformStore,
    private val auth: AuthService,
) {

    data class DocCommentBody(@field:NotBlank val body: String)

    data class DocCommentView(
        val id: Long,
        val docId: Long?,
        val author: String,
        val body: String,
        val createdAt: String?,
    )

    @GetMapping
    fun list(@RequestParam repoId: Long? = null, @RequestParam q: String? = null): List<io.spechub.spec.index.DocRecord> =
        if (!q.isNullOrBlank()) projection.searchDocs(q, repoId) else projection.listDocs(repoId)

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): io.spechub.spec.index.DocRecord =
        projection.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")

    @GetMapping("/{id}/comments")
    fun comments(@PathVariable id: Long): List<DocCommentView> {
        val doc = projection.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")
        // 投影重建后 doc.id 会变：按 repo + path 稳定寻址，返回时以当前 doc.id 覆盖快照列
        return platform.listCommentsForDoc(doc.repoId, doc.path).map { toView(it).copy(docId = doc.id) }
    }

    @PostMapping("/{id}/comments")
    fun addComment(@PathVariable id: Long, @RequestBody @Valid body: DocCommentBody, request: HttpServletRequest): DocCommentView {
        val user = auth.requireAuthenticated(request, "发表文档评论")
        val doc = projection.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")
        val comment = platform.addComment(doc.repoId, apiId = null, docId = doc.id, method = "", path = doc.path, author = user.username, body = body.body.trim())
        platform.addChangelog(doc.repoId, null, user.username, "doc.comment", "评论文档 ${doc.title}：${body.body.trim().take(40)}")
        return toView(comment)
    }

    private fun toView(c: CommentRecord): DocCommentView = DocCommentView(
        id = c.id!!, docId = c.docId, author = c.author, body = c.body, createdAt = c.createdAt,
    )
}