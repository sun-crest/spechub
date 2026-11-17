package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.Stage
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
 * E2 —— Repo 注册 REST：添加仓地址 / 分支 / 可选 token 注入（不落库）。
 * 注册即触发一次摄取（同步返回 api 统计），供立即验收；后续增量走 webhook（E6）/ 轮询（E7）。
 */

@RestController
@RequestMapping("/api")
class RepoController(
    private val ingestor: Ingestor,
    private val projection: ProjectionStore,
    private val repoService: RepoService,
    private val urlTokens: UrlTokenStore,
    private val auth: AuthService,
) {
    data class RegisterRepoRequest(
        @field:NotBlank val url: String,
        val branch: String? = null,
        val token: String? = null,
    )

    data class RegisterResponse(
        val repo: RepoRecord,
        val apiCount: Int,
        val schemaCount: Int,
        val versionCount: Int,
        val messages: List<String>,
    )

    @PostMapping("/repos")
    fun register(
        @RequestBody @Valid req: RegisterRepoRequest,
        request: jakarta.servlet.http.HttpServletRequest,
    ): RegisterResponse {
        auth.requireProjectRole(request, repoService.projectIdByName(DEFAULT_PROJECT), io.spechub.spec.index.Role.EDITOR, "注册仓库")
        val repo = repoService.register(DEFAULT_PROJECT, req.url.trim(), req.branch?.ifEmpty { null } ?: "main")
        if (req.token != null && req.token.isNotBlank()) {
            urlTokens.put(repo.id, injectToken(req.url.trim(), req.token.trim()))
        }
        val result = ingestor.ingest(repo, urlTokens.get(repo.id))
        return RegisterResponse(repo, result.apiCount, result.schemaCount, result.versionCount, result.messages)
    }

    @GetMapping("/repos")
    fun list(request: HttpServletRequest): List<RepoRecord> {
        val repoIds = auth.visibleRepoIds(request, Stage.RELEASE)
        return repoService.listReposByIds(repoIds)
    }

    @GetMapping("/repos/{id}")
    fun get(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): RepoRecord = auth.requireRepoVisible(request, id, Stage.RELEASE)

    @GetMapping("/repos/{id}/apis")
    fun apis(
        @PathVariable id: Long,
        request: HttpServletRequest,
        @RequestParam method: String? = null,
        @RequestParam tag: String? = null,
        @RequestParam q: String? = null,
        @RequestParam stage: String? = null,
    ): List<io.spechub.spec.index.ApiRecord> {
        val resolvedStage = stage ?: Stage.DRAFT
        if (resolvedStage !in setOf(Stage.DRAFT, Stage.RELEASE)) {
            throw ApiException(ApiErrorCode.VALIDATION, "stage ∈ draft | release")
        }
        auth.requireRepoVisible(request, id, resolvedStage)
        return projection.listApis(id, method?.uppercase(), tag, q, resolvedStage)
    }

    companion object {
        const val DEFAULT_PROJECT = "default"

        /** `https://host/path` → `https://oauth2:TOKEN@host/path`（仅内存使用） */
        internal fun injectToken(
            url: String,
            token: String,
        ): String =
            if (url.contains("://")) {
                val proto = url.substringBefore("://")
                val rest = url.substringAfter("://")
                if (rest.contains("@")) url else "$proto://oauth2:$token@$rest"
            } else {
                url
            }
    }
}
