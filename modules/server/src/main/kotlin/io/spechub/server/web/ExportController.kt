package io.spechub.server.web

import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.PlatformStore
import io.spechub.spec.index.Stage
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import io.spechub.spec.parser.export.HtmlPortalExporter
import io.spechub.spec.parser.export.MarkdownExporter
import io.spechub.spec.parser.export.OpenApi30Exporter
import io.spechub.spec.parser.export.PostmanExporter
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * J3/J4 —— 标准/文档导出（读索引投影重建 OpenApiDoc）：
 * - `GET /api/repos/{id}/export/openapi31|openapi30|postman`（J3）
 * - `GET /api/repos/{id}/export/{markdown|html}`（J4 文档导出；html 为自包含门户，可离线打开/对外发布）
 * - `?stage=release` 导出对外发布快照（§6.4 决策 5 消费端默认读 release）。
 */
@RestController
@RequestMapping("/api/repos/{id}/export")
class ExportController(
    private val exportService: ExportService,
    private val platform: PlatformStore,
) {

    @GetMapping("/{format}")
    fun export(@PathVariable id: Long, @PathVariable format: String, @RequestParam stage: String = Stage.DRAFT): ResponseEntity<String> {
        if (format !in setOf("openapi31", "openapi30", "postman", "markdown", "html")) {
            throw ApiException(
                ApiErrorCode.VALIDATION, "format ∈ openapi31 | openapi30 | postman | markdown | html",
                listOf(ApiErrorBody.FieldError("format", "format ∈ openapi31 | openapi30 | postman | markdown | html")),
            )
        }
        if (stage !in setOf(Stage.DRAFT, Stage.RELEASE)) {
            throw ApiException(ApiErrorCode.VALIDATION, "stage ∈ draft | release", listOf(ApiErrorBody.FieldError("stage", "stage ∈ draft | release")))
        }
        platform.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")

        val doc = exportService.reconstructOpenApi(id, stage)
        val (body, mediaType, ext) = when (format) {
            "openapi31" -> Triple(SpecModelIO.writeDocument(doc, SpecFormat.YAML, pretty = true), "text/yaml;charset=UTF-8", "yaml")
            "openapi30" -> Triple(OpenApi30Exporter.exportYaml(doc), "text/yaml;charset=UTF-8", "yaml")
            "postman" -> Triple(PostmanExporter.exportJson(doc), MediaType.APPLICATION_JSON.toString(), "json")
            "markdown" -> Triple(MarkdownExporter.export(doc), "text/markdown;charset=UTF-8", "md")
            "html" -> Triple(HtmlPortalExporter.export(doc), "text/html;charset=UTF-8", "html")
            else -> throw ApiException(ApiErrorCode.BAD_REQUEST, "format 不支持")
        }
        val disposition = "attachment; filename=spechub-$format.$ext"
        return ResponseEntity.ok().header("Content-Type", mediaType).header("Content-Disposition", disposition).body(body)
    }
}