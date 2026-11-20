package io.spechub.server.service

import io.spechub.server.repository.MembershipRepository
import io.spechub.server.repository.OrgMembershipRepository
import io.spechub.server.repository.ProjectRepository
import io.spechub.server.repository.TeamMemberRepository
import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserRepository
import io.spechub.server.repository.UserStatus
import io.spechub.server.repository.UserTokenRepository
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.UserRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 阶段 C（C4）：账号生命周期的数据库事务编排。
 *
 * - 禁用 / 重置：同一事务内改 `app_user.status` / `must_change_password` 并吊销全部 `user_token`；
 *   事务提交后由 Controller 删除 Redis 会话（跨存储不做分布式事务，删除失败由每请求状态校验兜底）。
 * - 删除：同一事务内清理 team_member / org_membership / membership 后删除用户（token / identity FK CASCADE）。
 * - 「最后一名可登录 org_admin」保护统一在此判定。
 */
@Service
class UserLifecycleService(
    private val users: UserRepository,
    private val tokens: UserTokenRepository,
    private val orgMemberships: OrgMembershipRepository,
    private val memberships: MembershipRepository,
    private val teamMembers: TeamMemberRepository,
    private val projects: ProjectRepository,
    private val orgs: OrgService,
    private val userService: UserService,
    private val audit: AuditService,
) {
    fun requireNotSelf(
        actorId: Long?,
        targetId: Long,
        action: String,
    ) {
        if (actorId == targetId) throw ApiException(ApiErrorCode.BAD_REQUEST, "不能${action}自己")
    }

    /** 目标用户必须属于当前组织（阶段 C 单组织；D1 多组织防线）。 */
    fun ensureSameOrg(
        userId: Long,
        orgId: Long,
    ) {
        orgMemberships.findByUserIdAndOrgId(userId, orgId)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
    }

    /** 最后一名「可登录（active|invited）的 active org_admin」不可禁用 / 降级 / 删除。 */
    fun ensureNotLastLoginnableOrgAdmin(
        targetUserId: Long,
        orgId: Long,
    ) {
        val membership = orgMemberships.findByUserIdAndOrgId(targetUserId, orgId) ?: return
        if (membership.orgRole != OrgService.ROLE_ADMIN || membership.status != OrgService.STATUS_ACTIVE) return
        val adminIds =
            orgMemberships
                .findAllByOrgIdOrderByUserIdAsc(orgId)
                .filter { it.orgRole == OrgService.ROLE_ADMIN && it.status == OrgService.STATUS_ACTIVE }
                .map { it.userId }
        val loginnable = users.findAllById(adminIds).count { it.status in UserStatus.LOGINNABLE }
        if (loginnable <= 1) {
            throw ApiException(ApiErrorCode.CONFLICT, "组织至少需要保留一名可登录的 org_admin，请先指定新的 org_admin")
        }
    }

    fun isLoginnableOrgAdmin(
        userId: Long,
        orgId: Long,
    ): Boolean {
        val membership = orgMemberships.findByUserIdAndOrgId(userId, orgId) ?: return false
        if (membership.orgRole != OrgService.ROLE_ADMIN || membership.status != OrgService.STATUS_ACTIVE) return false
        return users.findById(userId).orElse(null)?.status in UserStatus.LOGINNABLE
    }

    @Transactional
    fun disable(
        userId: Long,
        orgId: Long,
    ): UserRecord {
        val user = requireUser(userId)
        users.save(user.copy(status = UserStatus.DISABLED))
        revokeAllTokens(userId)
        audit.record(orgId, audit.currentActor(), "user.disable", "user", userId.toString(), "禁用用户 ${user.username}")
        return requireRecord(userId)
    }

    @Transactional
    fun enable(
        userId: Long,
        orgId: Long,
    ): UserRecord {
        val user = requireUser(userId)
        users.save(user.copy(status = UserStatus.ACTIVE))
        audit.record(orgId, audit.currentActor(), "user.enable", "user", userId.toString(), "启用用户 ${user.username}")
        return requireRecord(userId)
    }

    /** 管理端重置：置 mustChangePassword 并吊销全部令牌；会话由 Controller 在事务提交后删除。 */
    @Transactional
    fun resetPassword(
        userId: Long,
        orgId: Long,
    ): UserRecord {
        val user = requireUser(userId)
        users.save(user.copy(mustChangePassword = true))
        revokeAllTokens(userId)
        audit.record(orgId, audit.currentActor(), "user.password.reset", "user", userId.toString(), "重置用户 ${user.username} 密码")
        return requireRecord(userId)
    }

    @Transactional
    fun delete(
        userId: Long,
        orgId: Long,
    ): UserRecord {
        val user = requireUser(userId)
        if (projects.findAllByOwnerId(userId).isNotEmpty()) {
            throw ApiException(ApiErrorCode.CONFLICT, "该用户仍是项目 owner，请先转移项目所有权")
        }
        teamMembers.deleteAllByUserId(userId)
        orgMemberships.deleteAllByUserId(userId)
        memberships.deleteAllByUserId(userId)
        users.deleteById(userId)
        audit.record(orgId, audit.currentActor(), "user.delete", "user", userId.toString(), "删除用户 ${user.username}")
        return userService.recordOf(user)
    }

    /** 改组织角色；降级 org_admin 前保护最后一名可登录管理员。 */
    @Transactional
    fun setOrgRole(
        userId: Long,
        orgId: Long,
        role: String,
    ): UserRecord {
        val user = requireUser(userId)
        if (role != OrgService.ROLE_ADMIN) ensureNotLastLoginnableOrgAdmin(userId, orgId)
        orgs.ensureMembership(userId, orgId, role)
        audit.record(orgId, audit.currentActor(), "user.role", "user", userId.toString(), "用户 ${user.username} → $role")
        return userService.recordOf(user)
    }

    private fun revokeAllTokens(userId: Long) {
        tokens.findAllByUserIdOrderByCreatedAtDesc(userId).forEach { token ->
            if (token.revokedAt == null) tokens.save(token.copy(revokedAt = Instant.now()))
        }
    }

    private fun requireUser(userId: Long): UserEntity = users.findById(userId).orElse(null) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")

    private fun requireRecord(userId: Long): UserRecord = userService.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
}
