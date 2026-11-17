package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import io.spechub.spec.parser.ParseResult
import io.spechub.spec.parser.PostmanImporter
import io.spechub.spec.parser.SpecParser
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/*
 * J1/J2 —— 导入：把存量文档（Postman collection / Swagger 2.0）经归一化管道收编进平台。
 * - `POST /api/import/{type}`（type ∈ postman | swagger2），body {content, repoId?, path?}：
 *   * 先解析/升级为内部 OpenAPI 3.1（验证归一化管道，返回预览 + 统计）；
 *   * 传 `repoId`（+ 可选 `path`）→ 用 SpecWriter 写入真源仓（git commit+push）再摄取，
 *     存量文档真正进 git 真源（「导入落点=写入选定真源仓」，用户拍板）。
 * - 写回需要该 repo editor+（requireRepoRole）；仅预览需要登录。
 */
@RestController
@RequestMapping("/api/import")
class ImportController(
    private val repoService: RepoService,
    private val auth: AuthService,
    private val writer: SpecWriter,
    private val ingestor: Ingestor,
    private val urlTokens: UrlTokenStore,
) {
    data class ImportRequest(
        @field:NotBlank val content: String,
        val repoId: Long? = null,
        val path: String? = null,
    )

    data class WrittenInfo(
        val repoId: Long,
        val path: String,
        val commit: String,
        val branch: String,
    )

    data class ImportResponse(
        val type: String,
        val title: String,
        val apiCount: Int,
        val messages: List<String>,
        val specYaml: String,
        val written: WrittenInfo? = null,
    )

    @PostMapping("/{type}")
    fun import(
        @PathVariable type: String,
        @RequestBody @Valid body: ImportRequest,
        request: HttpServletRequest,
    ): ImportResponse {
        if (type !in setOf("postman", "swagger2")) {
            throw ApiException(ApiErrorCode.VALIDATION, "type ∈ postman | swagger2", listOf(ApiErrorBody.FieldError("type", "type ∈ postman | swagger2")))
        }
        auth.requireAuthenticated(request, "导入")

        val result =
            when (type) {
                "postman" -> PostmanImporter.importCollection(SpecModelIO.parseToTree(body.content))
                "swagger2" -> SpecParser.parse(body.content)
                else -> null
            }
        val doc =
            when (result) {
                is ParseResult.Success -> result.doc

                is ParseResult.Failure -> throw ApiException(
                    ApiErrorCode.IMPORT_INVALID,
                    "导入内容无法解析：${result.issues.joinToString("; ") { it.message }}",
                )

                null -> throw ApiException(ApiErrorCode.BAD_REQUEST, "未知导入类型")
            }

        val apiCount = doc.paths.values.sumOf { it.operations().size }
        val specYaml = SpecModelIO.writeDocument(doc, SpecFormat.YAML, pretty = true)

        var written: WrittenInfo? = null
        body.repoId?.let { repoId ->
            auth.requireRepoRole(request, repoId, Role.EDITOR, "导入写回")
            val repo = repoService.getRepo(repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
            val path =
                body.path?.takeIf { it.endsWith(".yaml") || it.endsWith(".yml") }
                    ?: "specs/import/$type-${System.currentTimeMillis()}.yaml"
            val wr = writer.writeSpec(repo, path, specYaml)
            ingestor.ingest(repo, urlTokens.get(repoId))
            written = WrittenInfo(repoId, path, wr.commit.take(12), wr.branch)
        }

        val messages = result.issues.map { it.message }
        return ImportResponse(type, doc.info.title, apiCount, messages, specYaml, written)
    }
}
