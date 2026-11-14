package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.SpecIndexStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** 只读查询扩展 + 写回端点的装配（F 组 MCP 工具集的 HTTP 后端） */
@RestController
@RequestMapping("/api")
class ApiQueryController(
    private val index: SpecIndexStore,
    private val writer: SpecWriter,
    private val ingestor: Ingestor,
    private val urlTokens: UrlTokenStore,
) {

    // ── 只读 · F 组 list_projects / get_api ───────────────────────────────

    @GetMapping("/projects")
    fun projects(): List<ProjectRecord> = index.listProjects()

    @GetMapping("/apis")
    fun search(
        @RequestParam q: String? = null,
        @RequestParam method: String? = null,
    ): List<ApiRecord> = index.listAllApis(q, method?.uppercase())

    @GetMapping("/apis/{id}")
    fun getApi(@PathVariable id: Long): ApiRecord =
        index.getApiById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "api $id 不存在")

    // ── 写回 · F 组 create_api / update_api（R1-C）─────────────────────────

    data class WriteSpecRequest(val path: String, val content: String)

    data class WriteSpecResponse(
        val repoId: Long,
        val path: String,
        val commit: String,
        val branch: String,
        val apiCount: Int,
    )

    @GetMapping("/repos/{id}/specs")
    fun listSpecs(@PathVariable id: Long): List<String> {
        val repo = index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")
        return writer.listSpecs(repo)
    }

    @GetMapping("/repos/{id}/spec")
    fun readSpec(@PathVariable id: Long, @RequestParam path: String): String {
        val repo = index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")
        return writer.readSpec(repo, path)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "spec $path 不存在")
    }

    @PutMapping("/repos/{id}/spec")
    fun writeSpec(@PathVariable id: Long, @RequestBody req: WriteSpecRequest): WriteSpecResponse {
        if (req.path.isBlank() || !(req.path.endsWith(".yaml") || req.path.endsWith(".yml") || req.path.endsWith(".json"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "path 须为 specs 下的 .yaml/.yml/.json 文件")
        }
        val repo = index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")
        val written = writer.writeSpec(repo, req.path.trim(), req.content)
        val ing = ingestor.ingest(repo, urlTokens.get(id))
        return WriteSpecResponse(repo.id, req.path.trim(), written.commit, written.branch, ing.apiCount)
    }
}