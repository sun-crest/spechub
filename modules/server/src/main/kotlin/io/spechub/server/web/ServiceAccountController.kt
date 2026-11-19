package io.spechub.server.web

import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.ServiceAccountService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.ServiceAccountRecord
import io.spechub.spec.index.Stage
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * C5：项目级服务账号（CI / MCP / CLI）。
 *
 * - 权限：项目 admin+ / org_admin；服务账号本身**不可管理服务账号**（requireHuman）。
 * - 跨项目：不可见项目统一 404；可见但角色不足 403。
 * - 明文只在创建 / 轮换响应返回一次；列表不含 hash / 明文。
 */
@RestController
@RequestMapping("/api/service-accounts")
class ServiceAccountController(
    private val auth: AuthService,
    private val projects: ProjectService,
    private val orgs: OrgService,
    private val serviceAccounts: ServiceAccountService,
) {
    data class CreateRequest(
        val projectId: Long,
        @field:NotBlank val name: String,
        val role: String = ServiceAccountService.ROLE_VIEWER,
        val expiresInDays: Long? = null,
    )

    data class RotateRequest(
        val expiresInDays: Long? = null,
    )

    data class AccountView(
        val id: Long,
        val projectId: Long,
        val name: String,
        val prefix: String,
        val role: String,
        val status: String,
        val expiresAt: String? = null,
        val lastUsedAt: String? = null,
        val revokedAt: String? = null,
        val createdAt: String? = null,
    )

    data class CreateResponse(
        val account: AccountView,
        val token: String,
    )

    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam projectId: Long,
    ): List<AccountView> {
        requireManage(request, projectId, "查看服务账号")
        return serviceAccounts.list(projectId).map(::toView)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @RequestBody @Valid body: CreateRequest,
    ): CreateResponse {
        requireManage(request, body.projectId, "创建服务账号")
        val project = projects.get(body.projectId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project ${body.projectId} 不存在")
        val orgId = project.orgId ?: orgs.defaultOrgId()
        val days = body.expiresInDays ?: 90
        val expiresAt = serviceAccounts.defaultExpiry(days)
        val issued = serviceAccounts.create(orgId, body.projectId, body.name, body.role, expiresAt)
        return CreateResponse(toView(issued.account), issued.token)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        request: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val account = serviceAccounts.find(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "服务账号 $id 不存在")
        requireManage(request, account.projectId, "吊销服务账号")
        serviceAccounts.revoke(id, account.projectId)
    }

    @PostMapping("/{id}/rotate")
    fun rotate(
        request: HttpServletRequest,
        @PathVariable id: Long,
        @RequestBody(required = false) body: RotateRequest?,
    ): CreateResponse {
        val account = serviceAccounts.find(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "服务账号 $id 不存在")
        requireManage(request, account.projectId, "轮换服务账号")
        val expiresAt: Instant? = body?.expiresInDays?.let { serviceAccounts.defaultExpiry(it) }
        val issued = serviceAccounts.rotate(id, account.projectId, expiresAt)
        return CreateResponse(toView(issued.account), issued.token)
    }

    /** 人类 + 项目 admin+ / org_admin；不可见项目 404，可见但权限不足 403。 */
    private fun requireManage(
        request: HttpServletRequest,
        projectId: Long,
        op: String,
    ) {
        auth.requireHuman(request, op)
        projects.get(projectId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        auth.requireProjectVisible(request, projectId, Stage.RELEASE)
        auth.requireProjectRole(request, projectId, Role.ADMIN, op)
    }

    private fun toView(record: ServiceAccountRecord): AccountView =
        AccountView(
            id = checkNotNull(record.id) { "已持久化服务账号必有 id" },
            projectId = record.projectId,
            name = record.name,
            prefix = record.prefix,
            role = record.role,
            status = record.status,
            expiresAt = record.expiresAt,
            lastUsedAt = record.lastUsedAt,
            revokedAt = record.revokedAt,
            createdAt = record.createdAt,
        )
}
