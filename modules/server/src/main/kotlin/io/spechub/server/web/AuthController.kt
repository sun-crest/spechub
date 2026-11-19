package io.spechub.server.web

import io.spechub.server.repository.UserStatus
import io.spechub.server.security.SessionAdminService
import io.spechub.server.service.ChangelogService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserLifecycleService
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
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
    private val passwordEncoder: PasswordEncoder,
    private val sessions: SessionAdminService,
    private val lifecycle: UserLifecycleService,
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
        /** 组织管理员是唯一组织管理标识（不再有平台级 admin 字段） */
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
        val password: String? = null,
        val orgRole: String = OrgService.ROLE_MEMBER,
    )

    data class UpdateUserRequest(
        val displayName: String? = null,
        val email: String? = null,
    )

    data class UpdateRoleRequest(
        @field:NotBlank val role: String,
    )

    data class CreateUserResponse(
        val user: UserView,
        val token: String,
        /** 邀请用户的一次性密码设置令牌（只返回一次） */
        val setupToken: String? = null,
    )

    data class ResetPasswordResponse(
        val user: UserView,
        /** 一次性 password_reset 令牌（只返回一次，24h） */
        val setupToken: String,
    )

    data class ChangePasswordRequest(
        val currentPassword: String? = null,
        val newPassword: String? = null,
    )

    data class SetupPasswordRequest(
        val token: String? = null,
        val newPassword: String? = null,
    )

    data class SetupPasswordResponse(
        val ok: Boolean,
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

    // ── 密码管理 ───────────────────────────────────────────────────────────

    /** 修改自己的密码：校验当前密码（首次改密 / 无密码时允许不带），吊销其它 Web 会话；API 令牌不自动吊销。 */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(
        req: HttpServletRequest,
        @RequestBody body: ChangePasswordRequest,
    ) {
        val user = auth.requireHuman(req, "修改密码")
        val userId = checkNotNull(user.id) { "当前用户必有 id" }
        validatePassword(body.newPassword, user.username, user.email)
        val newPassword = checkNotNull(body.newPassword)

        if (body.currentPassword != null) {
            val hash = users.passwordHash(userId)
            if (hash != null && !passwordEncoder.matches(body.currentPassword, hash)) {
                throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "当前密码错误")
            }
        } else if (user.hasPassword && !user.mustChangePassword) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "请输入当前密码",
                listOf(ApiErrorBody.FieldError("currentPassword", "请输入当前密码")),
            )
        }

        val encoded = checkNotNull(passwordEncoder.encode(newPassword)) { "密码编码失败" }
        users.setPassword(userId, encoded)
        sessions.deleteOtherSessions(user.username, req.getSession(false)?.id)
    }

    /** 一次性邀请 / 重置令牌设置密码；不自动登录，前端引导重新登录。 */
    @PostMapping("/password/setup")
    fun setupPassword(
        @RequestBody body: SetupPasswordRequest,
    ): SetupPasswordResponse {
        val raw =
            body.token?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ApiException(
                    ApiErrorCode.VALIDATION,
                    "缺少令牌",
                    listOf(ApiErrorBody.FieldError("token", "令牌不能为空")),
                )
        val resolved =
            userTokens.resolveSetup(raw)
                ?: throw ApiException(ApiErrorCode.BAD_REQUEST, "邀请 / 重置令牌无效或已过期")
        val user = resolved.user
        validatePassword(body.newPassword, user.username, user.email)
        val newPassword = checkNotNull(body.newPassword)

        val encoded = checkNotNull(passwordEncoder.encode(newPassword)) { "密码编码失败" }
        users.setPassword(
            checkNotNull(user.id) { "已持久化用户必有 id" },
            encoded,
            status = UserStatus.ACTIVE,
        )
        userTokens.markUsed(checkNotNull(resolved.token.id) { "已持久化令牌必有 id" })
        return SetupPasswordResponse(ok = true)
    }

    private fun validatePassword(
        password: String?,
        username: String,
        email: String?,
    ) {
        val errors = mutableListOf<ApiErrorBody.FieldError>()
        if (password.isNullOrBlank()) {
            errors += ApiErrorBody.FieldError("newPassword", "密码不能为空")
        } else {
            if (password.length !in 10..128) {
                errors += ApiErrorBody.FieldError("newPassword", "密码长度须为 10–128")
            }
            if (password.equals(username, ignoreCase = true) ||
                (!email.isNullOrBlank() && password.equals(email, ignoreCase = true))
            ) {
                errors += ApiErrorBody.FieldError("newPassword", "密码不能与用户名或邮箱相同")
            }
        }
        if (errors.isNotEmpty()) throw ApiException(ApiErrorCode.VALIDATION, "密码不符合要求", errors)
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
        val email =
            body
                .email
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }
        if (email != null && users.findByEmail(email) != null) {
            throw ApiException(ApiErrorCode.CONFLICT, "邮箱 $email 已被使用")
        }
        val hasPassword = !body.password.isNullOrBlank()
        if (hasPassword) validatePassword(body.password, username, email)
        val passwordHash = body.password?.let { checkNotNull(passwordEncoder.encode(it)) { "密码编码失败" } }

        val user =
            users.createOrgUser(
                username = username,
                displayName = body.displayName,
                email = email,
                orgRole = body.orgRole,
                status = if (hasPassword) UserStatus.ACTIVE else UserStatus.INVITED,
                passwordHash = passwordHash,
                mustChangePassword = true,
            )
        val userId = checkNotNull(user.id) { "用户创建后应有 id" }
        val apiToken = userTokens.issue(userId, "initial", UserTokenService.PURPOSE_API, Instant.now().plus(Duration.ofDays(90)))
        val setupToken =
            if (hasPassword) {
                null
            } else {
                userTokens.issue(userId, "invite", UserTokenService.PURPOSE_INVITE, Instant.now().plus(Duration.ofDays(7))).raw
            }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.create",
            detail = "创建组织用户 $username（${body.orgRole}${if (hasPassword) "" else "，邀请"}）",
            orgId = auth.currentOrgId(req),
        )
        return CreateUserResponse(toView(user, body.orgRole), apiToken.raw, setupToken)
    }

    @PatchMapping("/users/{id}")
    fun updateUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
        @RequestBody body: UpdateUserRequest,
    ): UserView {
        auth.requireOrgAdmin(req, "修改用户资料")
        val orgId = auth.currentOrgId(req)
        lifecycle.ensureSameOrg(id, orgId)
        users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")

        val email =
            body
                .email
                ?.let { if (it.isBlank()) "" else it.trim().lowercase() }
        if (!email.isNullOrBlank()) {
            val existing = users.findByEmail(email)
            if (existing != null && existing.id != id) throw ApiException(ApiErrorCode.CONFLICT, "邮箱 $email 已被使用")
        }
        val updated =
            users.updateProfile(id, body.displayName, email)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.update",
            detail = "更新组织用户 ${updated.username} 资料",
            orgId = orgId,
        )
        return toView(updated, orgs.orgRole(id, orgId))
    }

    @PostMapping("/users/{id}/role")
    fun setRole(
        req: HttpServletRequest,
        @PathVariable id: Long,
        @RequestBody @Valid body: UpdateRoleRequest,
    ): UserView {
        auth.requireOrgAdmin(req, "改组织角色")
        validateOrgRole(body.role, "role")
        val orgId = auth.currentOrgId(req)
        lifecycle.ensureSameOrg(id, orgId)
        val user = lifecycle.setOrgRole(id, orgId, body.role)
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

    @PostMapping("/users/{id}/disable")
    fun disableUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): UserView {
        val actor = auth.requireOrgAdmin(req, "禁用用户")
        val orgId = auth.currentOrgId(req)
        lifecycle.requireNotSelf(actor.id, id, "禁用")
        lifecycle.ensureSameOrg(id, orgId)
        lifecycle.ensureNotLastLoginnableOrgAdmin(id, orgId)

        val user = lifecycle.disable(id)
        sessions.deleteAllSessions(user.username)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.disable",
            detail = "禁用组织用户 ${user.username}（令牌已吊销、会话已删除）",
            orgId = orgId,
        )
        return toView(user, orgs.orgRole(id, orgId))
    }

    @PostMapping("/users/{id}/enable")
    fun enableUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): UserView {
        auth.requireOrgAdmin(req, "启用用户")
        val orgId = auth.currentOrgId(req)
        lifecycle.ensureSameOrg(id, orgId)
        val user = lifecycle.enable(id)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.enable",
            detail = "启用组织用户 ${user.username}（不恢复旧会话 / 令牌）",
            orgId = orgId,
        )
        return toView(user, orgs.orgRole(id, orgId))
    }

    @PostMapping("/users/{id}/password/reset")
    fun resetPassword(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): ResetPasswordResponse {
        val actor = auth.requireOrgAdmin(req, "重置用户密码")
        val orgId = auth.currentOrgId(req)
        lifecycle.requireNotSelf(actor.id, id, "重置密码")
        lifecycle.ensureSameOrg(id, orgId)

        val user = lifecycle.resetPassword(id)
        sessions.deleteAllSessions(user.username)
        val setup =
            userTokens.issue(
                checkNotNull(user.id) { "用户必有 id" },
                "password-reset",
                UserTokenService.PURPOSE_PASSWORD_RESET,
                Instant.now().plus(Duration.ofHours(24)),
            )
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.password.reset",
            detail = "重置组织用户 ${user.username} 密码（会话已删除、令牌已吊销）",
            orgId = orgId,
        )
        return ResetPasswordResponse(toView(user, orgs.orgRole(id, orgId)), setup.raw)
    }

    /** 兼容端点：吊销旧 legacy / api 令牌并签发一条新的 API 令牌（明文只返回一次）。 */
    @PostMapping("/users/{id}/token")
    fun resetToken(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): CreateUserResponse {
        auth.requireOrgAdmin(req, "重置令牌")
        val orgId = auth.currentOrgId(req)
        lifecycle.ensureSameOrg(id, orgId)
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        userTokens.revokeLoginTokens(id)
        val issued = userTokens.issue(id, "reset", UserTokenService.PURPOSE_API, Instant.now().plus(Duration.ofDays(90)))
        val orgRole = orgs.orgRole(id, orgId)
        return CreateUserResponse(toView(user, orgRole), issued.raw)
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val actor = auth.requireOrgAdmin(req, "删除用户")
        val orgId = auth.currentOrgId(req)
        lifecycle.requireNotSelf(actor.id, id, "删除")
        lifecycle.ensureSameOrg(id, orgId)
        lifecycle.ensureNotLastLoginnableOrgAdmin(id, orgId)

        val user = lifecycle.delete(id)
        sessions.deleteAllSessions(user.username)
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(req),
            action = "user.delete",
            detail = "删除组织用户 ${user.username}",
            orgId = orgId,
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
