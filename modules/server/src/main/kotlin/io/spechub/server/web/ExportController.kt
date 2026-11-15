package io.spechub.server.web

import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.Stage
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import io.spechub.spec.parser.export.OpenApi30Exporter
import io.spechub.spec.parser.export.PostmanExporter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * J3 —— 标准导出（读索引投影重建 OpenApiDoc）：
 * - `GET /api/repos/{id}/export/openapi31`（YAML） / `openapi30`（3.0 降级 YAML）/ `postman`（collection JSON）
 * - `?stage=release` 导出对外发布快照（§6.4 决策 5 消费端默认读 release）。
 */
@RestController
@RequestMapping("/api/repos/{id}/export")
class ExportController(
    private val exportService: ExportService,
    private val index: SpecIndexStore,
) {

    @GetMapping("/{format}")
    fun export(@PathVariable id: Long, @PathVariable format: String, @RequestParam stage: String = Stage.DRAFT): ResponseEntity<String> {
        if (format !in setOf("openapi31", "openapi30", "postman")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "format ∈ openapi31 | openapi30 | postman")
        }
        if (stage !in setOf(Stage.DRAFT, Stage.RELEASE)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "stage ∈ draft | release")
        }
        index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")

        val doc = exportService.reconstructOpenApi(id, stage)
        val (body, mediaType) = when (format) {
            "openapi31" -> SpecModelIO.writeDocument(doc, SpecFormat.YAML, pretty = true) to "text/yaml;charset=UTF-8"
            "openapi30" -> OpenApi30Exporter.exportYaml(doc) to "text/yaml;charset=UTF-8"
            "postman" -> PostmanExporter.exportJson(doc) to MediaType.APPLICATION_JSON.toString()
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "format 不支持")
        }
        val disposition = "attachment; filename=spechub-${format}.${if (format == "postman") "json" else "yaml"}"
        return ResponseEntity.ok().header("Content-Type", mediaType).header("Content-Disposition", disposition).body(body)
    }
}