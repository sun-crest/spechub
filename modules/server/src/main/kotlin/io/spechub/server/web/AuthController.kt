package io.spechub.server.web

import io.spechub.spec.index.Role
import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
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
 *
 * R4：用户域业务编排下沉至 `UserService`；本类只保留 HTTP 映射、声明式校验与 DTO 转换。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val index: SpecIndexStore,
    private val auth: AuthService,
    private val userService: UserService,
) {

    data class LoginRequest(val username: String? = null, @field:NotBlank val token: String)

    data class StatusResponse(val authEnabled: Boolean, val authenticated: Boolean, val username: String? = null)

    data class MeResponse(
        val authenticated: Boolean,
        val authEnabled: Boolean,
        val username: String? = null,
        val displayName: String? = null,
        val platformAdmin: Boolean = false,
        /** 对 default project 的角色（前端编辑可见性据此） */
        val role: String? = null,
        /** 全局角色视图：平台 admin 或项目 owner 视为可管理 */
        val canManage: Boolean = false,
    )

    data class UserResponse(
        val id: Long,
        val username: String,
        val displayName: String,
        val platformAdmin: Boolean,
        val role: String?,
        val createdAt: String? = null,
    )

    data class CreateUserRequest(@field:NotBlank val username: String, val displayName: String = "", val role: String = Role.VIEWER)

    data class UpdateRoleRequest(@field:NotBlank val role: String)

    data class CreateUserResponse(val user: UserResponse, val token: String)

    @GetMapping("/status")
    fun status(req: HttpServletRequest): StatusResponse {
        val user = auth.currentUser(req)
        return StatusResponse(auth.authEnabled, user != null, user?.username)
    }

    @PostMapping("/login")
    fun login(@RequestBody @Valid body: LoginRequest): StatusResponse {
        if (!auth.authEnabled) {
            throw ApiException(ApiErrorCode.AUTH_NOT_ENABLED, "后端未启用鉴权（未配 spechub.admin-token），无需登录")
        }
        // 兼容 H6：只传 token → 视为 admin 登录
        val user = auth.userForToken(body.token)
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
        val projectId = defaultProjectId()
        val role = auth.effectiveRole(user, projectId)
        return MeResponse(
            authenticated = true, authEnabled = true,
            username = user.username, displayName = user.displayName,
            platformAdmin = user.isPlatformAdmin, role = role,
            canManage = user.isPlatformAdmin || Role.rank(role) >= Role.rank(Role.ADMIN),
        )
    }

    // ── 成员管理（需要 default project admin+ 或平台 admin；MVP 单项目 default） ──

    @GetMapping("/users")
    fun users(req: HttpServletRequest): List<UserResponse> {
        val projectId = defaultProjectId()
        auth.requireProjectRole(req, projectId, Role.ADMIN, "查看成员")
        return userService.listUsers(projectId).map(::toResponse)
    }

    @PostMapping("/users")
    fun createUser(req: HttpServletRequest, @RequestBody @Valid body: CreateUserRequest): CreateUserResponse {
        val projectId = defaultProjectId()
        auth.requireProjectRole(req, projectId, Role.ADMIN, "创建用户")
        val created = userService.createUserWithMembership(
            username = body.username,
            displayName = body.displayName,
            role = body.role,
            projectId = projectId,
            actor = auth.currentUser(req),
        )
        return CreateUserResponse(toResponse(created.user), created.token)
    }

    @PostMapping("/users/{id}/role")
    fun setRole(req: HttpServletRequest, @PathVariable id: Long, @RequestBody @Valid body: UpdateRoleRequest): UserResponse {
        val projectId = defaultProjectId()
        auth.requireProjectRole(req, projectId, Role.ADMIN, "改角色")
        return toResponse(userService.setRole(id, body.role, projectId, auth.currentUser(req)))
    }

    @PostMapping("/users/{id}/token")
    fun resetToken(req: HttpServletRequest, @PathVariable id: Long): CreateUserResponse {
        val projectId = defaultProjectId()
        auth.requireProjectRole(req, projectId, Role.ADMIN, "重置令牌")
        val created = userService.resetToken(id, projectId)
        return CreateUserResponse(toResponse(created.user), created.token)
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteUser(req: HttpServletRequest, @PathVariable id: Long) {
        val projectId = defaultProjectId()
        auth.requireProjectRole(req, projectId, Role.ADMIN, "删除用户")
        userService.deleteUser(id, auth.currentUser(req))
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun defaultProjectId(): Long? = index.projectIdByName(RepoController.DEFAULT_PROJECT)

    private fun toResponse(summary: UserService.UserSummary): UserResponse = UserResponse(
        id = checkNotNull(summary.user.id) { "用户记录缺 id" },
        username = summary.user.username,
        displayName = summary.user.displayName,
        platformAdmin = summary.user.isPlatformAdmin,
        role = summary.role,
        createdAt = summary.user.createdAt,
    )
}
