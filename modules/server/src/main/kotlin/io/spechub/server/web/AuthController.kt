package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.service.ChangelogService
import io.spechub.server.service.OrgService
import io.spechub.server.service.RepoService
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
 * I4 —— RBAC 身份与成员管理（替换 H6 的 admin-token 占位，二者兼容）。
 * - GET  /api/auth/status          启用与当前认证状态（前端启动探测用）
 * - POST /api/auth/login           {username, token}：校验；{token} 兼容 admin-token
 * - GET  /api/auth/me              当前用户 + 对 default project 的角色
 * - GET  /api/auth/users           成员列表（需对 default project admin+ 或平台 admin）
 * - POST /api/auth/users           {username, displayName, role}：创建用户 + 加入 default project，返回一次性 token
 * - POST /api/auth/users/{id}/role {role}：改角色（同上权限）
 * - POST /api/auth/users/{id}/token    重置 token（admin+）
 * - DELETE /api/auth/users/{id}    删除用户（owner）
 * 本组端点自身放行（TokenAuthFilter 不拦 /api/auth 前缀），所以端点内自行做身份/角色校验。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val props: SpecHubProperties,
    private val changelogService: ChangelogService,
    private val repoService: RepoService,
    private val users: UserService,
    private val orgs: OrgService,
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
        /** 对 default project 的角色（前端编辑可见性据此） */
        val role: String? = null,
        /** 全局角色视图：平台 admin / org admin / 项目 admin+ 视为可管理 */
        val canManage: Boolean = false,
    )

    data class UserView(
        val id: Long,
        val username: String,
        val displayName: String,
        val platformAdmin: Boolean,
        val role: String?,
        val createdAt: String? = null,
    )

    data class CreateUserRequest(
        @field:NotBlank val username: String,
        val displayName: String = "",
        val role: String = Role.VIEWER,
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
            // 未启用鉴权：报告环境 + 未认证（前端据此免登录只读）
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
        val defaultProjectId = repoService.projectIdByName(RepoController.DEFAULT_PROJECT)
        val role = auth.effectiveRole(user, defaultProjectId)
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
            role = role,
            canManage = orgAdmin || Role.rank(role) >= Role.rank(Role.ADMIN),
        )
    }

    // ── 成员管理（需要 default project admin+ 或平台 admin；MVP 单项目 default） ──

    @GetMapping("/users")
    fun users(req: HttpServletRequest): List<UserView> {
        auth.requireProjectRole(req, repoService.projectIdByName(RepoController.DEFAULT_PROJECT), Role.ADMIN, "查看成员")
        val projectId = repoService.projectIdByName(RepoController.DEFAULT_PROJECT)
        return users.listUsers().map { user ->
            UserView(
                id = user.id as Long,
                username = user.username,
                displayName = user.displayName,
                platformAdmin = user.isPlatformAdmin,
                role = projectId?.let { users.membershipRole(user.id as Long, it) },
                createdAt = user.createdAt,
            )
        }
    }

    @PostMapping("/users")
    fun createUser(
        req: HttpServletRequest,
        @RequestBody @Valid body: CreateUserRequest,
    ): CreateUserResponse {
        auth.requireProjectRole(req, repoService.projectIdByName(RepoController.DEFAULT_PROJECT), Role.ADMIN, "创建用户")
        val username = body.username.trim()
        if (!Role.ALL.contains(body.role)) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "role ∈ ${Role.ALL}",
                listOf(ApiErrorBody.FieldError("role", "role ∈ ${Role.ALL}")),
            )
        }
        if (users.findByUsername(username) != null) {
            throw ApiException(ApiErrorCode.CONFLICT, "用户 $username 已存在")
        }
        val token = AuthService.generateToken()
        val projectId = repoService.projectIdByName(RepoController.DEFAULT_PROJECT)
        // 用户 + 成员在管理库同一事务内创建；changelog 仍在投影库，跨库不做分布式事务
        val user = users.createUserWithMembership(username, body.displayName, AuthService.sha256(token), projectId, body.role)
        changelogService.add(repoId = null, apiId = null, actor = auth.currentActor(req), action = "user.create", detail = "创建用户 $username（$body.role）")
        return CreateUserResponse(toView(user, projectId), token)
    }

    @PostMapping("/users/{id}/role")
    fun setRole(
        req: HttpServletRequest,
        @PathVariable id: Long,
        @RequestBody @Valid body: UpdateRoleRequest,
    ): UserView {
        auth.requireProjectRole(req, repoService.projectIdByName(RepoController.DEFAULT_PROJECT), Role.ADMIN, "改角色")
        if (!Role.ALL.contains(body.role)) {
            throw ApiException(ApiErrorCode.VALIDATION, "role ∈ ${Role.ALL}", listOf(ApiErrorBody.FieldError("role", "role ∈ ${Role.ALL}")))
        }
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "平台 admin 角色不可改")
        val projectId = repoService.projectIdByName(RepoController.DEFAULT_PROJECT)
        if (projectId != null) users.setMembership(id, projectId, body.role)
        changelogService.add(repoId = null, apiId = null, actor = auth.currentActor(req), action = "user.role", detail = "用户 ${user.username} → $body.role")
        return toView(user, projectId)
    }

    @PostMapping("/users/{id}/token")
    fun resetToken(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ): CreateUserResponse {
        auth.requireProjectRole(req, repoService.projectIdByName(RepoController.DEFAULT_PROJECT), Role.ADMIN, "重置令牌")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        val token = AuthService.generateToken()
        // 无公开 updateToken 字段 → 删除重建不可取（保留 id/role）。这里用内部更新：
        users.updateToken(id, AuthService.sha256(token))
        return CreateUserResponse(toView(user, repoService.projectIdByName(RepoController.DEFAULT_PROJECT)), token)
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(
        req: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val actor = auth.currentUser(req)
        auth.requireProjectRole(req, repoService.projectIdByName(RepoController.DEFAULT_PROJECT), Role.ADMIN, "删除用户")
        val user = users.findById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除平台 admin")
        if (actor != null && actor.id == id) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除自己")
        users.deleteUser(id)
        changelogService.add(repoId = null, apiId = null, actor = auth.currentActor(req), action = "user.delete", detail = "删除用户 ${user.username}")
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun toView(
        user: UserRecord,
        projectId: Long?,
    ): UserView =
        UserView(
            id = user.id as Long,
            username = user.username,
            displayName = user.displayName,
            platformAdmin = user.isPlatformAdmin,
            role = projectId?.let { users.membershipRole(user.id as Long, it) },
            createdAt = user.createdAt,
        )
}
