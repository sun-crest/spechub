package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.ingest.SpecFileInvalidException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ResponseStatusException

/**
 * 摄取接入（E2/E3 对外面）：
 *  - `POST /api/repos`  注册 + 立即摄取一个仓（Repo 注册 REST，P0 合并为"添加即摄取"）
 *  - `GET  /api/projects/{p}/repos/{org}/{repo}/apis`  列出已索引的 API（验收面）
 */
@RestController
@RequestMapping("/api")
class IngestController(private val ingestor: Ingestor) {

    data class IngestRequest(
        @NotBlank val project: String,
        @NotBlank val org: String,
        @NotBlank val name: String,
        @NotBlank val remoteUrl: String,
        val branch: String = "main",
    )

    @PostMapping("/repos")
    fun ingest(@Valid @RequestBody req: IngestRequest): Map<String, Any> {
        val result = try {
            ingestor.ingest(
                project = req.project, org = req.org, name = req.name,
                remoteUrl = req.remoteUrl, branch = req.branch, source = "api",
            )
        } catch (e: SpecFileInvalidException) {
            throw ResponseStatusException(HttpStatusCode.valueOf(422), e.message, e)
        }
        val specFile: Any = result.specFile ?: "none"
        return mapOf<String, Any>(
            "ok" to true,
            "unchanged" to result.unchanged,
            "specFile" to specFile,
            "repoId" to result.repoId,
        )
    }
}