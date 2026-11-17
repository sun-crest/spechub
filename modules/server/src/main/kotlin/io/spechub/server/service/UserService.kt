package io.spechub.server.service

import io.spechub.server.web.AuthService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.UserRecord
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/*
 * R4 —— 用户域业务编排（规范 §1.2）。
 *
 * 职责边界：
 * - Controller 只保留 HTTP 映射、声明式校验与 DTO 转换；
 * - 角色值域、同名冲突、平台 admin 保护、不能删除自己等业务规则在此判定；
 * - changelog 统一在此落（产品回看），日志另行（§6.2）。
 *
 * 原子性（§5 / R6）：用户创建走 `SpecIndexStore.createUserWithMembership` 单事务复合原语，
 * 中途失败不留「半用户」（app_user 与 membership 同事务提交/回滚）。
 */
@Service
class UserService(
    private val index: SpecIndexStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 成员列表：附带指定项目上的角色（projectId 为 null 时角色为空） */
    fun listUsers(projectId: Long?): List<UserSummary> = index.listUsers().map { user -> UserSummary(user, roleOf(user.id, projectId)) }

    /** 创建用户 + 加入项目 + changelog；返回一次性明文 token（仅本次响应出现） */
    fun createUserWithMembership(
        username: String,
        displayName: String,
        role: String,
        projectId: Long?,
        actor: UserRecord?,
    ): CreatedUser {
        requireValidRole(role)
        val name = username.trim()
        if (index.findByUsername(name) != null) {
            throw ApiException(ApiErrorCode.CONFLICT, "用户 $name 已存在")
        }
        val token = AuthService.generateToken()
        val user =
            index.createUserWithMembershipWithChangelog(
                username = name,
                displayName = displayName.ifBlank { name },
                tokenHash = AuthService.sha256(token),
                projectId = projectId,
                role = role,
                actor = actorName(actor),
                action = "user.create",
                detail = "创建用户 $name（$role）",
            )
        log.info("创建用户 user={} role={} actor={}", name, role, actorName(actor))
        return CreatedUser(UserSummary(user, roleOf(user.id, projectId)), token)
    }

    /** 改角色（平台 admin 不可改）+ changelog */
    fun setRole(
        id: Long,
        role: String,
        projectId: Long?,
        actor: UserRecord?,
    ): UserSummary {
        requireValidRole(role)
        val user = index.findUserById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "平台 admin 角色不可改")
        index.setMembershipWithChangelog(
            userId = id,
            projectId = projectId,
            role = role,
            actor = actorName(actor),
            action = "user.role",
            detail = "用户 ${user.username} → $role",
        )
        log.info("改角色 user={} role={} actor={}", user.username, role, actorName(actor))
        return UserSummary(user, roleOf(id, projectId))
    }

    /** 重置令牌（旧 token 立即失效；明文仅本次响应出现） */
    fun resetToken(
        id: Long,
        projectId: Long?,
    ): CreatedUser {
        val user = index.findUserById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        val token = AuthService.generateToken()
        index.updateUserToken(id, AuthService.sha256(token))
        log.info("重置用户令牌 user={}", user.username)
        return CreatedUser(UserSummary(user, roleOf(user.id, projectId)), token)
    }

    /** 删除用户（平台 admin 与自己不可删）+ changelog */
    fun deleteUser(
        id: Long,
        actor: UserRecord?,
    ) {
        val user = index.findUserById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $id 不存在")
        if (user.isPlatformAdmin) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除平台 admin")
        if (actor != null && actor.id == id) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能删除自己")
        index.deleteUserWithChangelog(
            id = id,
            actor = actorName(actor),
            action = "user.delete",
            detail = "删除用户 ${user.username}",
        )
        log.info("删除用户 user={} actor={}", user.username, actorName(actor))
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    /** 角色值域校验：可注解表达的部分在 DTO；跨端点的业务值域在 Service（§3.2） */
    private fun requireValidRole(role: String) {
        if (!Role.ALL.contains(role)) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "role ∈ ${Role.ALL}",
                listOf(ApiErrorBody.FieldError("role", "role ∈ ${Role.ALL}")),
            )
        }
    }

    private fun roleOf(
        userId: Long?,
        projectId: Long?,
    ): String? = if (userId != null && projectId != null) index.membershipRole(userId, projectId) else null

    private fun actorName(actor: UserRecord?): String = actor?.username ?: "anonymous"

    /** 用户 + 其在项目上的角色（Controller 据此转 UserResponse） */
    data class UserSummary(
        val user: UserRecord,
        val role: String?,
    )

    /** 创建/重置令牌的结果：用户快照 + 一次性明文 token */
    data class CreatedUser(
        val user: UserSummary,
        val token: String,
    )
}
