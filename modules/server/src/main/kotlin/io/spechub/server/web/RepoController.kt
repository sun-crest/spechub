package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.repository.RepoBindingEntity
import io.spechub.server.service.GitConnectionService
import io.spechub.server.service.RepoBindingService
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/*
 * E2 / 阶段 A —— Repo 注册 REST（ADR §4.4 / §8.2）：
 * - `POST /api/repos` 必须携带 `projectId`；注册即创建逻辑仓库 + 首个 `active` binding。
 * - `managed`（默认）本地托管；`external` 为外部真源（`connection_id` 阶段 A 允许为空）。
 * - 注册后按 active binding 触发一次同步摄取（后续增量走 webhook E6 / 轮询 E7）。
 * - token 只进内存（UrlTokenStore），不落库、不落盘。
 */

@RestController
@RequestMapping("/api")
class RepoController(
    private val ingestor: Ingestor,
    private val projection: ProjectionStore,
    private val repoService: RepoService,
    private val bindingService: RepoBindingService,
    private val connections: GitConnectionService,
    private val urlTokens: UrlTokenStore,
    private val auth: AuthService,
) {
    data class RegisterRepoRequest(
        @field:NotNull val projectId: Long,
        @field:NotBlank val url: String,
        val branch: String? = null,
        val token: String? = null,
        /** managed | external */
        val kind: String = RepoBindingEntity.KIND_MANAGED,
        val connectionId: Long? = null,
        val externalRepoId: String? = null,
    )

    data class RegisterResponse(
        val repo: RepoRecord,
        val bindingId: Long?,
        val apiCount: Int,
        val schemaCount: Int,
        val versionCount: Int,
        val messages: List<String>,
    )

    @PostMapping("/repos")
    fun register(
        @RequestBody @Valid req: RegisterRepoRequest,
        request: HttpServletRequest,
    ): RegisterResponse {
        auth.requireProjectRole(request, req.projectId, Role.EDITOR, "注册仓库")
        if (req.kind != RepoBindingEntity.KIND_MANAGED && req.kind != RepoBindingEntity.KIND_EXTERNAL) {
            throw ApiException(ApiErrorCode.VALIDATION, "kind ∈ managed | external")
        }
        // connection 必须属于同一组织（阶段 A 只做归属校验，不管理密钥）
        if (req.connectionId != null) {
            val connection =
                connections.get(req.connectionId)
                    ?: throw ApiException(ApiErrorCode.VALIDATION, "git connection ${req.connectionId} 不存在")
            if (connection.orgId != auth.currentOrgId(request)) {
                throw ApiException(ApiErrorCode.VALIDATION, "git connection ${req.connectionId} 不属于当前组织")
            }
        }
        val repo =
            try {
                repoService.register(
                    projectId = req.projectId,
                    url = req.url.trim(),
                    branch = req.branch?.ifEmpty { null } ?: "main",
                    kind = req.kind,
                    connectionId = req.connectionId,
                    externalRepoId = req.externalRepoId,
                )
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiErrorCode.VALIDATION, e.message ?: "仓库参数不合法")
            } catch (e: IllegalStateException) {
                throw ApiException(ApiErrorCode.CONFLICT, e.message ?: "仓库注册状态冲突")
            }
        if (req.token != null && req.token.isNotBlank()) {
            urlTokens.put(repo.id, injectToken(req.url.trim(), req.token.trim()))
        }
        val result = ingestor.ingest(repo, urlTokens.get(repo.id))
        return RegisterResponse(
            repo = repo,
            bindingId = bindingService.active(repo.id)?.id,
            apiCount = result.apiCount,
            schemaCount = result.schemaCount,
            versionCount = result.versionCount,
            messages = result.messages,
        )
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
