package io.spechub.server.web

import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.server.service.RepoService
import io.spechub.spec.index.Stage
import io.spechub.spec.parser.export.MarkdownExporter
import io.spechub.server.config.SpecHubProperties
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/*
 * J4 —— docx / PDF 导出（pandoc 子进程软依赖，§7 选型：docx(pandoc) / pdf(子进程)，免受 POI/Aspose 授权费用）。
 * - `POST /api/repos/{id}/document/{docx|pdf}?stage=`：先 MarkdownExporter 渲染 → pandoc 转目标格式
 *   → 落 `artifacts` 卷（docs/）+ 返回文件流下载。
 * - pandoc 未安装（或转换失败）→ 409 提示请服务端安装 pandoc（软依赖，不拖垮其他能力）。
 */
@RestController
@RequestMapping("/api/repos/{id}/document")
class DocumentController(
    private val exportService: ExportService,
    private val repoService: RepoService,
    private val props: SpecHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{format}")
    fun render(
        @PathVariable id: Long,
        @PathVariable format: String,
        @RequestParam stage: String = Stage.DRAFT,
    ): ResponseEntity<ByteArray> {
        if (format !in setOf("docx", "pdf")) {
            throw ApiException(ApiErrorCode.VALIDATION, "format ∈ docx | pdf", listOf(ApiErrorBody.FieldError("format", "format ∈ docx | pdf")))
        }
        if (stage !in setOf(Stage.DRAFT, Stage.RELEASE)) {
            throw ApiException(ApiErrorCode.VALIDATION, "stage ∈ draft | release", listOf(ApiErrorBody.FieldError("stage", "stage ∈ draft | release")))
        }
        repoService.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")

        val doc = exportService.reconstructOpenApi(id, stage)
        val md = MarkdownExporter.export(doc)

        // pandoc 软依赖：未安装 / 转换失败 → 友好报错（服务端安装 pandoc 即解锁）
        val mdTmp = try {
            Files.createTempFile("spechub-doc", ".md").also { Files.writeString(it, md) }
        } catch (e: IOException) {
            log.warn("写入临时 md 失败：{}", e.message)
            throw ApiException(ApiErrorCode.INTERNAL, "写入临时文件失败")
        }
        val outPath = props.artifactsPath().resolve("docs").resolve("repo-$id-${format}-$stage.$format")
        Files.createDirectories(outPath.parent)
        try {
            val pb = ProcessBuilder("pandoc", mdTmp.toString(), "-f", "markdown", "-t", format, "-o", outPath.toString())
                .redirectErrorStream(true)
            val proc = pb.start()
            val exit = proc.waitFor()
            if (exit != 0) {
                val err = proc.inputStream.bufferedReader().readText().take(240)
                throw ApiException(ApiErrorCode.UPSTREAM_ERROR, "pandoc 转换失败（退出码 $exit）：$err")
            }
        } catch (e: IOException) {
            throw ApiException(
                ApiErrorCode.SERVICE_UNAVAILABLE,
                "服务端未安装 pandoc，无法生成 $format（软依赖：安装 pandoc 后此能力即解锁）",
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiException(ApiErrorCode.INTERNAL, "pandoc 被中断")
        } finally {
            Files.deleteIfExists(mdTmp)
        }

        val out = try {
            Files.readAllBytes(outPath)
        } catch (e: IOException) {
            log.warn("读取导出产物失败：{}", e.message)
            throw ApiException(ApiErrorCode.INTERNAL, "读取导出产物失败")
        }
        val mediaType = if (format == "docx") "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "application/pdf"
        return ResponseEntity.ok()
            .header("Content-Type", mediaType)
            .header("Content-Disposition", "attachment; filename=spechub-$format.$format")
            .body(out)
    }
}