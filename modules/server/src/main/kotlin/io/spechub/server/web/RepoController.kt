package io.spechub.server.web

import io.spechub.server.service.SpecWriteService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.Role
import io.spechub.spec.index.SpecIndexStore
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
    private val index: SpecIndexStore,
    private val auth: AuthService,
    private val specWriteService: SpecWriteService,
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
        auth.requireProjectRole(request, index.projectIdByName(DEFAULT_PROJECT), Role.EDITOR, "注册仓库")
        // R5：注册 + token 注入 + 首次摄取统一在 SpecWriteService；R9：注册 changelog 与 INFO 双记录
        val outcome =
            specWriteService.registerRepo(
                DEFAULT_PROJECT,
                req.url,
                req.branch,
                req.token,
                auth.currentActor(request),
            )
        return RegisterResponse(
            repo = outcome.repo,
            apiCount = outcome.ingest.apiCount,
            schemaCount = outcome.ingest.schemaCount,
            versionCount = outcome.ingest.versionCount,
            messages = outcome.ingest.messages,
        )
    }

    @GetMapping("/repos")
    fun list(): List<RepoRecord> = index.listRepos()

    @GetMapping("/repos/{id}")
    fun get(
        @PathVariable id: Long,
    ): RepoRecord = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")

    @GetMapping("/repos/{id}/apis")
    fun apis(
        @PathVariable id: Long,
        @RequestParam method: String? = null,
        @RequestParam tag: String? = null,
        @RequestParam q: String? = null,
        @RequestParam stage: String? = null,
    ) = index.listApis(id, method?.uppercase(), tag, q, stage ?: "draft")

    companion object {
        const val DEFAULT_PROJECT = "default"
    }
}
