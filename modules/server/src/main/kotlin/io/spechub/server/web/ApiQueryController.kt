package io.spechub.server.web

import io.spechub.server.service.SpecWriteService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.Role
import io.spechub.spec.index.SpecIndexStore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

/** 只读查询扩展 + 写回端点的装配（F 组 MCP 工具集的 HTTP 后端） */
@RestController
@RequestMapping("/api")
class ApiQueryController(
    private val index: SpecIndexStore,
    private val writer: SpecWriter,
    private val specWriteService: SpecWriteService,
    private val auth: AuthService,
) {

    // ── 只读 · F 组 list_projects / get_api ───────────────────────────────

    @GetMapping("/projects")
    fun projects(): List<ProjectRecord> = index.listProjects()

    @GetMapping("/apis")
    fun search(
        @RequestParam q: String? = null,
        @RequestParam method: String? = null,
        @RequestParam stage: String? = null,
    ): List<ApiRecord> = index.listAllApis(q, method?.uppercase(), stage ?: "draft")

    @GetMapping("/apis/{id}")
    fun getApi(@PathVariable id: Long): ApiRecord =
        index.getApiById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "api $id 不存在")

    // ── 写回 · F 组 create_api / update_api（R1-C）─────────────────────────

    data class WriteSpecRequest(@field:NotBlank val path: String, val content: String)

    data class WriteSpecResponse(
        val repoId: Long,
        val path: String,
        val commit: String,
        val branch: String,
        val apiCount: Int,
        /** I8 评审流：启鉴权下写回先入评审队列，非空表示待批准；无鉴权直写时为 null */
        val reviewId: Long? = null,
        val status: String? = null,
        val message: String = "",
    )

    @GetMapping("/repos/{id}/specs")
    fun listSpecs(@PathVariable id: Long): List<String> {
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        return writer.listSpecs(repo)
    }

    @GetMapping("/repos/{id}/spec")
    fun readSpec(@PathVariable id: Long, @RequestParam path: String): String {
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        return writer.readSpec(repo, path)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "spec $path 不存在")
    }

    @PutMapping("/repos/{id}/spec")
    fun writeSpec(@PathVariable id: Long, @RequestBody @Valid req: WriteSpecRequest, request: jakarta.servlet.http.HttpServletRequest): WriteSpecResponse {
        auth.requireRepoRole(request, id, Role.EDITOR, "写回 spec")
        if (!(req.path.endsWith(".yaml") || req.path.endsWith(".yml") || req.path.endsWith(".json"))) {
            throw ApiException(
                ApiErrorCode.VALIDATION, "path 须为 specs 下的 .yaml/.yml/.json 文件",
                listOf(ApiErrorBody.FieldError("path", "path 须为 .yaml/.yml/.json")),
            )
        }
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")

        // R5：直写 / 评审分支统一在 SpecWriteService（鉴权启停是唯一判定点）
        val outcome = specWriteService.writeOrQueue(
            repo = repo,
            path = req.path.trim(),
            content = req.content,
            actor = auth.currentActor(request),
        )
        return WriteSpecResponse(
            repoId = outcome.repoId,
            path = outcome.path,
            commit = outcome.commit,
            branch = outcome.branch,
            apiCount = outcome.apiCount,
            reviewId = outcome.reviewId,
            status = outcome.status,
            message = outcome.message,
        )
    }
}