package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.repository.RepoBindingEntity
import io.spechub.server.service.AuditService
import io.spechub.server.service.ChangelogService
import io.spechub.server.service.GitConnectionService
import io.spechub.server.service.RepoBindingService
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * 阶段 A / ADR §4.4 —— 真源绑定（single-primary binding）：
 * - GET    /api/repos/{id}/bindings                         绑定列表（可见项目）
 * - POST   /api/repos/{id}/bindings                         创建 candidate（revert 前不对外服务）
 * - POST   /api/repos/{id}/bindings/{bindingId}/verify      与当前 active 比对（diff）
 * - POST   /api/repos/{id}/bindings/{bindingId}/activate    flip 为 active（project owner / org_admin）
 * - POST   /api/repos/{id}/bindings/{bindingId}/retire      退休非 active 绑定
 * - DELETE /api/repos/{id}/bindings/{bindingId}             删除 retired（回滚窗口结束后）
 *
 * 纪律：
 * - 摄取 / 写回只认 active binding；candidate 不对外服务；retired 不可读写。
 * - flip 事务内完成「旧 active → retired、candidate → active」，单主唯一约束兜底并发（409）。
 * - flip 后立即按新 active 全量重建该仓投影；写入失败不回滚 flip（semantics: 真源已切换）。
 */

@RestController
@RequestMapping("/api/repos/{repoId}/bindings")
class RepoBindingController(
    private val repoService: RepoService,
    private val bindingService: RepoBindingService,
    private val connections: GitConnectionService,
    private val ingestor: Ingestor,
    private val urlTokens: UrlTokenStore,
    private val changelogService: ChangelogService,
    private val auth: AuthService,
    private val audit: AuditService,
) {
    data class BindingView(
        val id: Long,
        val repoId: Long,
        val kind: String,
        val connectionId: Long?,
        val externalRepoId: String?,
        val remoteUrl: String,
        val branch: String,
        val status: String,
        val createdAt: String,
        val retiredAt: String?,
        /** retired 绑定的可删除时间（回滚窗口结束）；其余为 null */
        val deleteAfter: String?,
    )

    data class CreateBindingRequest(
        @field:NotBlank val remoteUrl: String,
        val kind: String = RepoBindingEntity.KIND_MANAGED,
        val branch: String? = null,
        val connectionId: Long? = null,
        val externalRepoId: String? = null,
    )

    data class ActivateBindingRequest(
        /** true = 明确接受 verify 差异（以候选为准）；默认 false 时先 verify */
        val confirm: Boolean = false,
    )

    data class ActivateResponse(
        val binding: BindingView,
        val verify: RepoBindingService.VerifyResult?,
        val ingest: Ingestor.IngestResult?,
        val ingestError: String? = null,
    )

    @GetMapping
    fun list(
        @PathVariable repoId: Long,
        request: HttpServletRequest,
    ): List<BindingView> {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        return bindingService.list(repoId).map(::toView)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable repoId: Long,
        @RequestBody @Valid body: CreateBindingRequest,
        request: HttpServletRequest,
    ): BindingView {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        auth.requireRepoRole(request, repoId, Role.ADMIN, "创建真源绑定")
        // connection 必须存在且属于当前组织（阶段 A 只做归属校验）
        if (body.connectionId != null) {
            val connection =
                connections.get(body.connectionId)
                    ?: throw ApiException(ApiErrorCode.VALIDATION, "git connection ${body.connectionId} 不存在")
            if (connection.orgId != auth.currentOrgId(request)) {
                throw ApiException(ApiErrorCode.VALIDATION, "git connection ${body.connectionId} 不属于当前组织")
            }
        }
        val binding =
            domain {
                bindingService.createCandidate(
                    repoId = repoId,
                    kind = body.kind,
                    remoteUrl = body.remoteUrl,
                    branch = body.branch?.ifEmpty { null } ?: "main",
                    connectionId = body.connectionId,
                    externalRepoId = body.externalRepoId,
                )
            }
        changelogService.add(
            repoId = repoId,
            apiId = null,
            actor = auth.currentActor(request),
            action = "repo.binding.create",
            detail = "创建候选真源 ${binding.kind} ${binding.remoteUrl}（分支 ${binding.branch}）",
        )
        audit.recordCurrent(
            auth.currentOrgId(request),
            "repo.binding.create",
            "repo",
            repoId.toString(),
            "创建候选真源 ${binding.kind} ${binding.remoteUrl}（分支 ${binding.branch}）",
        )
        return toView(binding)
    }

    @PostMapping("/{bindingId}/verify")
    fun verify(
        @PathVariable repoId: Long,
        @PathVariable bindingId: Long,
        request: HttpServletRequest,
    ): RepoBindingService.VerifyResult {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        auth.requireRepoRole(request, repoId, Role.ADMIN, "校验真源绑定")
        requireBinding(repoId, bindingId)
        val verify = bindingService.verify(repoId, bindingId)
        audit.recordCurrent(auth.currentOrgId(request), "repo.binding.verify", "repo", repoId.toString(), "校验真源绑定 #$bindingId（${verify.status}）")
        return verify
    }

    @PostMapping("/{bindingId}/activate")
    fun activate(
        @PathVariable repoId: Long,
        @PathVariable bindingId: Long,
        @RequestBody(required = false) body: ActivateBindingRequest?,
        request: HttpServletRequest,
    ): ActivateResponse {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        auth.requireRepoRole(request, repoId, Role.OWNER, "切换真源绑定")
        val candidate = requireBinding(repoId, bindingId)

        // 未显式确认时先比对：有差异/无法比对要求调用方确认（避免静默选择真源）
        val verify =
            if (candidate.status == RepoBindingEntity.STATUS_ACTIVE || body?.confirm == true) {
                null
            } else {
                bindingService.verify(repoId, bindingId)
            }
        if (verify != null &&
            (verify.status == RepoBindingService.VERIFY_DIVERGED || verify.status == RepoBindingService.VERIFY_ERROR)
        ) {
            throw ApiException(
                ApiErrorCode.CONFLICT,
                "候选与当前 active 不一致（${verify.status}），请核对 diff 后以 confirm=true 重试",
            )
        }

        val activated =
            try {
                bindingService.activate(repoId, bindingId)
            } catch (e: DataIntegrityViolationException) {
                throw ApiException(ApiErrorCode.CONFLICT, "并发切换冲突：已有其他绑定成为 active，请刷新后重试")
            } catch (e: IllegalArgumentException) {
                throw ApiException(ApiErrorCode.VALIDATION, e.message ?: "绑定状态不允许激活")
            } catch (e: IllegalStateException) {
                throw ApiException(ApiErrorCode.CONFLICT, e.message ?: "绑定状态冲突")
            }
        // 旧真源的 token 只属于旧来源，flip 后立即作废，避免注入到新真源
        urlTokens.remove(repoId)

        val repo = checkNotNull(repoService.getRepo(repoId)) { "repo $repoId 不存在" }
        var ingest: Ingestor.IngestResult? = null
        var ingestError: String? = null
        try {
            ingest = ingestor.ingest(repo, urlTokens.get(repoId))
        } catch (e: Exception) {
            ingestError = e.message?.take(300) ?: "摄取失败"
        }
        changelogService.add(
            repoId = repoId,
            apiId = null,
            actor = auth.currentActor(request),
            action = "repo.binding.activate",
            detail =
                "切换真源 → ${activated.kind} ${activated.remoteUrl}（分支 ${activated.branch}）" +
                    (ingestError?.let { "；摄取失败：$it" } ?: "；已重建索引"),
        )
        audit.recordCurrent(
            auth.currentOrgId(request),
            "repo.binding.activate",
            "repo",
            repoId.toString(),
            "切换真源 #$bindingId → ${activated.kind} ${activated.remoteUrl}",
        )
        return ActivateResponse(toView(activated), verify, ingest, ingestError)
    }

    @PostMapping("/{bindingId}/retire")
    fun retire(
        @PathVariable repoId: Long,
        @PathVariable bindingId: Long,
        request: HttpServletRequest,
    ): BindingView {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        auth.requireRepoRole(request, repoId, Role.OWNER, "退休真源绑定")
        requireBinding(repoId, bindingId)
        val binding = domain { bindingService.retire(repoId, bindingId) }
        changelogService.add(
            repoId = repoId,
            apiId = null,
            actor = auth.currentActor(request),
            action = "repo.binding.retire",
            detail = "退休真源绑定 #${binding.id}（${binding.kind} ${binding.remoteUrl}）",
        )
        audit.recordCurrent(
            auth.currentOrgId(request),
            "repo.binding.retire",
            "repo",
            repoId.toString(),
            "退休真源绑定 #$bindingId",
        )
        return toView(binding)
    }

    @DeleteMapping("/{bindingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable repoId: Long,
        @PathVariable bindingId: Long,
        request: HttpServletRequest,
    ) {
        auth.requireRepoVisible(request, repoId, Stage.RELEASE)
        auth.requireRepoRole(request, repoId, Role.OWNER, "删除真源绑定")
        requireBinding(repoId, bindingId)
        domain { bindingService.deleteRetired(repoId, bindingId) }
        changelogService.add(
            repoId = repoId,
            apiId = null,
            actor = auth.currentActor(request),
            action = "repo.binding.delete",
            detail = "删除已退休真源绑定 #$bindingId",
        )
        audit.recordCurrent(
            auth.currentOrgId(request),
            "repo.binding.delete",
            "repo",
            repoId.toString(),
            "删除已退休真源绑定 #$bindingId",
        )
    }

    private fun requireBinding(
        repoId: Long,
        bindingId: Long,
    ): RepoBindingEntity =
        bindingService.list(repoId).firstOrNull { it.id == bindingId }
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "binding $bindingId 不存在")

    private fun toView(binding: RepoBindingEntity): BindingView =
        BindingView(
            id = checkNotNull(binding.id) { "binding 必有 id" },
            repoId = binding.repoId,
            kind = binding.kind,
            connectionId = binding.connectionId,
            externalRepoId = binding.externalRepoId,
            remoteUrl = binding.remoteUrl,
            branch = binding.branch,
            status = binding.status,
            createdAt = binding.createdAt.toString(),
            retiredAt = binding.retiredAt?.toString(),
            deleteAfter = bindingService.deleteAfter(binding)?.toString(),
        )

    /** 领域校验异常 → 统一 API 错误码（Service 不依赖 web）。 */
    private inline fun <T> domain(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiErrorCode.VALIDATION, e.message ?: "参数不合法")
        } catch (e: IllegalStateException) {
            throw ApiException(ApiErrorCode.CONFLICT, e.message ?: "状态冲突")
        }
}
