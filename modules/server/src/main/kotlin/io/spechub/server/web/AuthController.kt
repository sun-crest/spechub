package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.UserRecord
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * I4 / 阶段 A —— 认证与组织用户管理（ADR §4.2 / §7）。
 * - GET  /api/auth/status          启用与当前认证状态
 * - POST /api/auth/login           用户名 + token
 * - GET  /api/auth/me              当前用户 + 组织上下文 + 各项目角色
 * - GET  /api/auth/users           组织用户目录（org_admin）
 * - POST /api/auth/users           创建组织用户（org_admin，默认 org_member）
 * - POST /api/auth/users/{id}/role 改组织角色（org_admin）
 * - POST /api/auth/users/{id}/token 重置 token（org_admin）
 * - DELETE /api/auth/users/{id}    删除组织用户（org_admin）
 *
 * 项目成员与项目角色由 `/api/projects/{id}/members` 管理，本控制器不再硬编码 default 项目。
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
) {
    data class LoginRequest(
        val username: String? = null,
        @field:NotBlank val token: String,
    )

    data class StatusResponse(
        val authEnabled: Boolean,
        val authenticated: Boolean,
        val username: String? = null,
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
        val username: String? = null,
        val displayName: String? = null,
        val platformAdmin: Boolean = false,
        /** 组织管理员（含平台 admin 兼容） */
        val orgAdmin: Boolean = false,
        /** 当前用户的组织列表（阶段 A 只有一个默认组织） */
        val orgs: List<OrgView> = emptyList(),
        val currentOrgId: Long? = null,
        val currentOrgSlug: String? = null,
        /** 兼容字段：阶段 A 起不再返回 default 项目角色，前端用 projectRoles。 */
        val role: String? = null,
        /** projectId → 当前用户在该项目的角色 */
        val projectRoles: Map<Long, String> = emptyMap(),
        /** 当前用户所在的团队（B3；org_admin 不自动列出全部团队） */
        val teams: List<MeTeamView> = emptyList(),
        /** 全局角色视图：org_admin 或任一项目 admin+ 视为可管理 */
        val canManage: Boolean = false,
    )

    data class UserView(
        val id: Long,
        val username: String,
        val displayName: String,
        val platformAdmin: Boolean,
        /** 项目角色（org 目录接口不再返回；项目成员端点返回） */
        val role: String? = null,
        /** 组织角色：org_admin / org_member */
        val orgRole: String? = null,
        val createdAt: String? = null,
    )

    data class CreateUserRequest(
        @field:NotBlank val username: String,
        val displayName: String = "",
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
        val user = auth.currentUser(req)
        return StatusResponse(auth.authEnabled, user != null, user?.username)
    }

    @PostMapping("/login")
    fun login(
        @RequestBody @Valid body: LoginRequest,
    ): StatusResponse {
        if (!auth.authEnabled) {
            throw ApiException(ApiErrorCode.AUTH_NOT_ENABLED, "后端未启用鉴权（未配 spechub.admin-token），无需登录")
        }
        // 兼容 H6：只传 token → 视为 admin 登录
        val user =
            auth.userForToken(body.token)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "令牌无效")
        if (body.username != null && body.username.isNotBlank() && body.username != user.username) {
            throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "用户名与令牌不匹配")
        }
        return StatusResponse(true, true, user.username)
    }

    @GetMapping("/me")
    fun me(req: HttpServletRequest): MeResponse {
        val user = auth.currentUser(req)
        if (user == null || !auth.authEnabled) {
            return MeResponse(authenticated = user != null, authEnabled = auth.authEnabled)
        }
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
        return MeResponse(
            authenticated = true,
            authEnabled = true,
            username = user.username,
            displayName = user.displayName,
            platformAdmin = user.isPlatformAdmin,
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
        val token = AuthService.generateToken()
        val user = users.createOrgUser(username, body.displayName, AuthService.sha256(token), body.orgRole)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.create",
            detail = "创建组织用户 $username（${body.orgRole}）",
            orgId = auth.currentOrgId(req),
        )
        return CreateUserResponse(toView(user, body.orgRole), token)
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
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "平台 admin 角色不可改")
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

    @PostMapping("/users/{id}/token")
    fun resetToken(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): CreateUserResponse {
        auth.requireOrgAdmin(req, "重置令牌")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        val token = AuthService.generateToken()
        users.updateToken(id, AuthService.sha256(token))
        val orgRole = orgs.orgRole(id, auth.currentOrgId(req))
        return CreateUserResponse(toView(user, orgRole), token)
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val actor = auth.requireOrgAdmin(req, "删除用户")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除平台 admin")
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
            platformAdmin = user.isPlatformAdmin,
            orgRole = orgRole,
            createdAt = user.createdAt,
        )
}
