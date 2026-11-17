package io.spechub.server.web

import io.spechub.spec.index.ChangelogAction
import io.spechub.server.service.ChangelogService
import io.spechub.spec.index.ProjectionStore
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import io.spechub.server.service.ReleaseService
import io.spechub.server.service.RepoService

/*
 * I6 —— 双层版本：draft（仓内最新）→ release（对外发布快照）。
 * §6.4 决策 5：消费者默认读 release 快照，draft 继续演进互不影响。
 * - POST /repos/{id}/promote  发布某 spec 文件的最新 draft 为 release（同 path 其它版本降 draft）
 * - GET  /repos/{id}/versions  版本历史（含 stage），按 path 过滤时展示发布状态
 * - GET  /repos/{id}/changelog  该 repo 变更记录（I5 铺底）
 */
@RestController
@RequestMapping("/api")
class ReleaseController(
    private val projection: ProjectionStore,
    private val changelogService: ChangelogService,
    private val repoService: RepoService,
    private val releaseService: ReleaseService,
    private val auth: AuthService,
) {

    data class PromoteRequest(@field:NotBlank val path: String)

    data class PromoteResponse(
        val repoId: Long,
        val path: String,
        val versionId: Long,
        val specHash: String,
        val stage: String,
        val message: String,
    )

    @PostMapping("/repos/{id}/promote")
    fun promote(@PathVariable id: Long, @RequestBody @Valid req: PromoteRequest, request: jakarta.servlet.http.HttpServletRequest): PromoteResponse {
        auth.requireRepoRole(request, id, io.spechub.spec.index.Role.EDITOR, "发布 release")
        val path = req.path.trim()
        val latest = projection.listVersionsOfPath(id, path).firstOrNull()
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 的 $path 无版本可发布（先摄取）")
        // 先落管理库 release 真源，再重放投影（跨库非原子；两步均幂等，失败可重试）
        releaseService.promote(id, path, latest.specHash)
        val promoted = projection.promotePath(id, path, latest.specHash)
            ?: throw IllegalStateException("release 记录已写入，但投影 promote 失败：repo=$id path=$path hash=${latest.specHash}")
        changelogService.add(
            repoId = id, apiId = null, actor = "spechub-bot", action = ChangelogAction.PROMOTE,
            detail = "发布 ${promoted.path} → release（版本 #${promoted.id}，specHash ${promoted.specHash.take(12)}…）",
        )
        return PromoteResponse(
            id, promoted.path, promoted.id, promoted.specHash, promoted.stage,
            "已发布为 release：${promoted.path}（specHash ${promoted.specHash.take(12)}…）",
        )
    }

    @GetMapping("/repos/{id}/versions")
    fun versions(@PathVariable id: Long, @RequestParam path: String? = null): List<io.spechub.spec.index.VersionRecord> {
        repoService.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        return if (path != null) projection.listVersionsOfPath(id, path) else projection.listVersions(id)
    }

    @GetMapping("/repos/{id}/changelog")
    fun changelog(@PathVariable id: Long, @RequestParam limit: Int = 50) = changelogService.list(repoId = id, apiId = null, limit = limit)

    @GetMapping("/changelog")
    fun globalChangelog(@RequestParam limit: Int = 50) = changelogService.list(repoId = null, apiId = null, limit = limit)

    @GetMapping("/apis/{id}/changelog")
    fun apiChangelog(@PathVariable id: Long) = changelogService.list(repoId = null, apiId = id, limit = 50)
}