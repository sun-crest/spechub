package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserService
import io.spechub.server.service.UserTokenService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/*
 * 阶段 C —— 认证状态与组织用户管理（ADR §4.2 / §7）。
 * - POST /api/auth/login 由 Spring Security JSON filter 接管（本控制器不注册）。
 * - GET  /api/auth/status          认证状态 / OIDC 探测
 * - GET  /api/auth/me              当前主体 + 组织上下文 + 项目角色 + CSRF
 * - GET  /api/auth/users           组织用户目录（org_admin）
 * - POST /api/auth/users           创建组织用户（org_admin）
 * - POST /api/auth/users/{id}/role 改组织角色（org_admin）
 * - POST /api/auth/users/{id}/token 兼容端点：吊销旧令牌并签发新 API 令牌
 * - DELETE /api/auth/users/{id}    删除组织用户（org_admin）
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val changelogService: ChangelogService,
    private val projectService: ProjectService,
    private val users: UserService,
    private val orgs: OrgService,
    private val teams: TeamService,
    private val auth: AuthService,
    private val userTokens: UserTokenService,
) {
    data class StatusResponse(
        val authEnabled: Boolean,
        val authenticated: Boolean,
        val authMethod: String? = null,
        val username: String? = null,
        val oidcEnabled: Boolean = false,
        val oidcLoginUrl: String? = null,
    )

    data class OrgView(
        val id: Long,
        val name: String,
        val slug: String,
        val role: String,
    )

    data class MeTeamView(
        val id: Long,
        val name: String,
        val role: String,
    )

    data class MeResponse(
        val authenticated: Boolean,
        val authEnabled: Boolean,
        val authMethod: String? = null,
        val username: String? = null,
        val displayName: String? = null,
        val email: String? = null,
        val status: String? = null,
        val csrfToken: String? = null,
        val mustChangePassword: Boolean = false,
        val hasPassword: Boolean = false,
        /** 组织管理员是唯一组织管理标识（不再有 platformAdmin） */
        val orgAdmin: Boolean = false,
        val oidcEnabled: Boolean = false,
        val oidcLoginUrl: String? = null,
        /** 当前用户的组织列表（阶段 C 仍为单组织） */
        val orgs: List<OrgView> = emptyList(),
        val currentOrgId: Long? = null,
        val currentOrgSlug: String? = null,
        /** 兼容字段：阶段 A 起不再返回 default 项目角色，前端用 projectRoles。 */
        val role: String? = null,
        /** projectId → 当前主体在该项目的角色 */
        val projectRoles: Map<Long, String> = emptyMap(),
        /** 当前用户所在的团队（服务账号不返回团队） */
        val teams: List<MeTeamView> = emptyList(),
        /** 全局角色视图：org_admin 或任一项目 admin+ 视为可管理 */
        val canManage: Boolean = false,
    )

    data class UserView(
        val id: Long,
        val username: String,
        val displayName: String,
        val email: String? = null,
        val status: String = "active",
        val mustChangePassword: Boolean = false,
        /** 项目角色（org 目录接口不再返回；项目成员端点返回） */
        val role: String? = null,
        /** 组织角色：org_admin / org_member */
        val orgRole: String? = null,
        val createdAt: String? = null,
    )

    data class CreateUserRequest(
        @field:NotBlank val username: String,
        val displayName: String = "",
        val email: String? = null,
        val orgRole: String = OrgService.ROLE_MEMBER,
    )

    data class UpdateRoleRequest(
        @field:NotBlank val role: String,
    )

    data class CreateUserResponse(
        val user: UserView,
        val token: String,
    )

    @GetMapping("/status")
    fun status(req: HttpServletRequest): StatusResponse {
        val authenticated = auth.currentAuthentication() != null
        return StatusResponse(
            authEnabled = true,
            authenticated = authenticated,
            authMethod = authMethod(req),
            username = auth.currentService()?.name ?: auth.currentUser(req)?.username,
        )
    }

    @GetMapping("/me")
    fun me(req: HttpServletRequest): MeResponse {
        val service = auth.currentService()
        if (service != null) {
            val org = orgs.get(service.orgId)
            return MeResponse(
                authenticated = true,
                authEnabled = true,
                authMethod = "bearer",
                displayName = service.name,
                status = "active",
                currentOrgId = org?.id,
                currentOrgSlug = org?.slug,
                projectRoles = mapOf(service.projectId to service.role),
                canManage = false,
            )
        }

        val user = auth.currentUser(req) ?: return MeResponse(authenticated = false, authEnabled = true)
        val currentOrg = auth.currentOrg(req)
        val orgId = checkNotNull(currentOrg.id) { "org 必有 id" }
        val orgAdmin = auth.isOrgAdmin(user, orgId)
        val orgViews =
            user.id?.let { uid ->
                orgs.membershipsOf(uid).mapNotNull { membership ->
                    orgs.get(membership.orgId)?.let { org ->
                        OrgView(
                            id = checkNotNull(org.id) { "org 必有 id" },
                            name = org.name,
                            slug = org.slug,
                            role = membership.orgRole,
                        )
                    }
                }
            } ?: emptyList()
        val projectRoles =
            projectService
                .list(orgId)
                .mapNotNull { project ->
                    project.id?.let { projectId ->
                        auth.effectiveRole(user, projectId)?.let { projectId to it }
                    }
                }.toMap()
        val teamViews =
            user.id?.takeIf { it > 0 }?.let { uid ->
                teams
                    .teamsOf(uid)
                    .mapNotNull { membership ->
                        membership.team.id?.let { MeTeamView(it, membership.team.name, membership.role) }
                    }
            } ?: emptyList()
        val csrfToken = (req.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        return MeResponse(
            authenticated = true,
            authEnabled = true,
            authMethod = authMethod(req),
            username = user.username,
            displayName = user.displayName,
            email = user.email,
            status = user.status,
            csrfToken = csrfToken,
            mustChangePassword = user.mustChangePassword,
            hasPassword = user.hasPassword,
            orgAdmin = orgAdmin,
            orgs = orgViews,
            currentOrgId = orgId,
            currentOrgSlug = currentOrg.slug,
            projectRoles = projectRoles,
            teams = teamViews,
            canManage = orgAdmin || projectRoles.values.any { Role.rank(it) >= Role.rank(Role.ADMIN) },
        )
    }

    // ── 组织用户目录（org_admin） ───────────────────────────────────────────

    @GetMapping("/users")
    fun users(req: HttpServletRequest): List<UserView> {
        auth.requireOrgAdmin(req, "查看组织成员")
        val orgId = auth.currentOrgId(req)
        val orgRoles = orgs.membersOf(orgId).associate { it.userId to it.orgRole }
        return users
            .listUsers()
            .filter { user -> user.id?.let { orgRoles.containsKey(it) } == true }
            .map { user -> toView(user, user.id?.let { orgRoles[it] }) }
    }

    @PostMapping("/users")
    fun createUser(
        req: HttpServletRequest,
        @RequestBody @Valid body: CreateUserRequest,
    ): CreateUserResponse {
        auth.requireOrgAdmin(req, "创建组织用户")
        val username = body.username.trim()
        validateOrgRole(body.orgRole, "orgRole")
        if (users.findByUsername(username) != null) {
            throw ApiException(ApiErrorCode.CONFLICT, "用户 $username 已存在")
        }
        val user = users.createOrgUser(username, body.displayName, body.email, body.orgRole)
        val userId = checkNotNull(user.id) { "用户创建后应有 id" }
        val issued = userTokens.issue(userId, "initial", UserTokenService.PURPOSE_API, Instant.now().plus(Duration.ofDays(90)))
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.create",
            detail = "创建组织用户 $username（${body.orgRole}）",
            orgId = auth.currentOrgId(req),
        )
        return CreateUserResponse(toView(user, body.orgRole), issued.raw)
    }

    @PostMapping("/users/{id}/role")
    fun setRole(
        req: HttpServletRequest,
        @PathVariable id: Long,
        @RequestBody @Valid body: UpdateRoleRequest,
    ): UserView {
        auth.requireOrgAdmin(req, "改组织角色")
        validateOrgRole(body.role, "role")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        val orgId = auth.currentOrgId(req)
        orgs.ensureMembership(id, orgId, body.role)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.role",
            detail = "组织用户 ${user.username} → ${body.role}",
            orgId = orgId,
        )
        return toView(user, body.role)
    }

    /** 兼容端点：吊销该用户全部令牌并签发一条新的 API 令牌（明文只返回一次）。 */
    @PostMapping("/users/{id}/token")
    fun resetToken(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): CreateUserResponse {
        auth.requireOrgAdmin(req, "重置令牌")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        userTokens.revokeAll(id)
        val issued = userTokens.issue(id, "reset", UserTokenService.PURPOSE_API, Instant.now().plus(Duration.ofDays(90)))
        val orgRole = orgs.orgRole(id, auth.currentOrgId(req))
        return CreateUserResponse(toView(user, orgRole), issued.raw)
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val actor = auth.requireOrgAdmin(req, "删除用户")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (actor.id == id) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除自己")
        users.deleteUser(id)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.delete",
            detail = "删除组织用户 ${user.username}",
            orgId = auth.currentOrgId(req),
        )
    }

    /** session：浏览器会话（Spring Session）；bearer：Authorization: Bearer（用户令牌 / 服务账号）。 */
    private fun authMethod(req: HttpServletRequest): String = if (req.getHeader("Authorization")?.startsWith("Bearer ", ignoreCase = true) == true) "bearer" else "session"

    private fun validateOrgRole(
        role: String,
        field: String,
    ) {
        val allowed = setOf(OrgService.ROLE_ADMIN, OrgService.ROLE_MEMBER)
        if (!allowed.contains(role)) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "$field ∈ $allowed",
                listOf(ApiErrorBody.FieldError(field, "$field ∈ $allowed")),
            )
        }
    }

    private fun toView(
        user: UserRecord,
        orgRole: String?,
    ): UserView =
        UserView(
            id = checkNotNull(user.id) { "user 必有 id" },
            username = user.username,
            displayName = user.displayName,
            email = user.email,
            status = user.status,
            mustChangePassword = user.mustChangePassword,
            orgRole = orgRole,
            createdAt = user.createdAt,
        )
}
