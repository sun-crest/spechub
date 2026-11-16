package io.spechub.server.web

import io.spechub.spec.index.CommentRecord
import io.spechub.spec.index.SpecIndexStore
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
 * - GET/POST /api/docs/{id}/comments   Doc 评论（I5 comment 表 doc_id 复用）
 * 渲染在 Web（react-markdown）；Doc↔Api 链接 = 正文「关联接口」节（front-matter related，K3）。
 */
@RestController
@RequestMapping("/api/docs")
class DocController(
    private val index: SpecIndexStore,
    private val auth: AuthService,
) {

    data class DocCommentBody(@field:NotBlank val body: String)

    data class DocCommentResponse(
        val id: Long,
        val docId: Long?,
        val author: String,
        val body: String,
        val createdAt: String?,
    )

    @GetMapping
    fun list(@RequestParam repoId: Long? = null, @RequestParam q: String? = null): List<io.spechub.spec.index.DocRecord> =
        if (!q.isNullOrBlank()) index.searchDocs(q, repoId) else index.listDocs(repoId)

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): io.spechub.spec.index.DocRecord =
        index.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")

    @GetMapping("/{id}/comments")
    fun comments(@PathVariable id: Long): List<DocCommentResponse> {
        index.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")
        return index.listCommentsForDoc(id).map(::toResponse)
    }

    @PostMapping("/{id}/comments")
    fun addComment(@PathVariable id: Long, @RequestBody @Valid body: DocCommentBody, request: HttpServletRequest): DocCommentResponse {
        val user = auth.requireAuthenticated(request, "发表文档评论")
        val doc = index.getDoc(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "doc $id 不存在")
        val comment = index.addCommentWithChangelog(
            repoId = doc.repoId, apiId = null, docId = doc.id,
            method = "", path = "", author = user.username, body = body.body.trim(),
            actor = user.username, action = "doc.comment",
            detail = "评论文档 ${doc.title}：${body.body.trim().take(40)}",
        )
        return toResponse(comment)
    }

    private fun toResponse(c: CommentRecord): DocCommentResponse = DocCommentResponse(
        id = checkNotNull(c.id) { "文档评论记录缺 id" }, docId = c.docId, author = c.author, body = c.body, createdAt = c.createdAt,
    )
}