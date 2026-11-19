package io.spechub.server.service

import io.spechub.server.repository.MembershipEntity
import io.spechub.server.repository.MembershipRepository
import io.spechub.server.repository.OrgMembershipRepository
import io.spechub.server.repository.TeamMemberRepository
import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserRepository
import io.spechub.server.repository.UserStatus
import io.spechub.spec.index.UserRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 用户 / 成员域 Service（阶段 C）。
 *
 * - 用户是全局身份；密码只存 Argon2id hash（C3 写入）；令牌统一走 [UserTokenService]。
 * - `bootstrapAdmin` 只确保 admin 用户、默认组织 org_admin 与 admin-token 的 legacy 令牌；
 *   **不覆盖 status / password_hash**，被禁用的 admin 重启后仍保持禁用。
 * - changelog 跨库不做分布式事务：调用方按固定次序编排（选型文档 §4）。
 */
@Service
class UserService(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val orgMemberships: OrgMembershipRepository,
    private val teamMembers: TeamMemberRepository,
    private val orgs: OrgService,
    private val userTokens: UserTokenService,
) {
    companion object {
        /** bootstrap admin 用户名（由 spechub.admin-token 支撑） */
        const val ADMIN_USERNAME = "admin"
    }

    /** 启动时 bootstrap admin（幂等：存在则只刷新 legacy 令牌与 org_admin 关系）。 */
    @Transactional
    fun bootstrapAdmin(rawAdminToken: String): UserRecord {
        val existing = users.findByUsername(ADMIN_USERNAME)
        val saved =
            existing
                ?: users.save(
                    UserEntity(
                        username = ADMIN_USERNAME,
                        displayName = ADMIN_USERNAME,
                        status = UserStatus.ACTIVE,
                        createdAt = Instant.now(),
                    ),
                )
        val userId = checkNotNull(saved.id) { "用户创建/更新后应有 id" }
        orgs.ensureMembership(userId, orgs.defaultOrgId(), OrgService.ROLE_ADMIN)
        userTokens.replaceLegacy(userId, rawAdminToken)
        return users.findById(userId).orElse(saved).toRecord()
    }

    fun findById(id: Long): UserRecord? = users.findById(id).orElse(null)?.toRecord()

    /** 仅用于密码校验；不对外返回 hash。 */
    fun passwordHash(userId: Long): String? = users.findById(userId).orElse(null)?.passwordHash

    /** 设置密码并清 mustChangePassword；设置密码即视为激活（邀请 / 重置流程）。 */
    @Transactional
    fun setPassword(
        userId: Long,
        passwordHash: String,
        status: String? = null,
    ): Boolean {
        val entity = users.findById(userId).orElse(null) ?: return false
        users.save(
            entity.copy(
                passwordHash = passwordHash,
                passwordUpdatedAt = Instant.now(),
                mustChangePassword = false,
                status = status ?: entity.status,
            ),
        )
        return true
    }

    fun findByUsername(username: String): UserRecord? = users.findByUsername(username)?.toRecord()

    fun findByEmail(email: String): UserRecord? = users.findByEmail(email)?.toRecord()

    fun listUsers(): List<UserRecord> = users.findAllOrdered().map { it.toRecord() }

    fun findByIds(ids: Collection<Long>): Map<Long, UserRecord> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            users
                .findAllById(ids)
                .mapNotNull { entity -> entity.id?.let { id -> id to entity.toRecord() } }
                .toMap()
        }

    /** 创建组织用户（不加入任何项目）；项目成员关系由项目成员端点维护。 */
    @Transactional
    fun createOrgUser(
        username: String,
        displayName: String,
        email: String? = null,
        orgRole: String = OrgService.ROLE_MEMBER,
        status: String = UserStatus.ACTIVE,
        passwordHash: String? = null,
        mustChangePassword: Boolean = false,
    ): UserRecord {
        val saved =
            users.save(
                UserEntity(
                    username = username,
                    displayName = displayName.ifBlank { username },
                    email = normalizeEmail(email),
                    passwordHash = passwordHash,
                    passwordUpdatedAt = passwordHash?.let { Instant.now() },
                    status = status,
                    mustChangePassword = mustChangePassword,
                    createdAt = Instant.now(),
                ),
            )
        val userId = checkNotNull(saved.id) { "用户创建后应回填 id" }
        orgs.ensureMembership(userId, orgs.defaultOrgId(), orgRole)
        return saved.toRecord()
    }

    /** 创建用户并加入默认组织；projectId 非空时同时加入项目。 */
    @Transactional
    fun createUserWithMembership(
        username: String,
        displayName: String,
        projectId: Long?,
        role: String,
        email: String? = null,
        status: String = UserStatus.ACTIVE,
    ): UserRecord {
        val saved =
            users.save(
                UserEntity(
                    username = username,
                    displayName = displayName.ifBlank { username },
                    email = normalizeEmail(email),
                    status = status,
                    createdAt = Instant.now(),
                ),
            )
        val userId = checkNotNull(saved.id) { "用户创建后应回填 id" }
        orgs.ensureMembership(userId, orgs.defaultOrgId(), OrgService.ROLE_MEMBER)
        if (projectId != null) {
            memberships.save(MembershipEntity(userId = userId, projectId = projectId, role = role))
        }
        return saved.toRecord()
    }

    /**
     * 创建用户并签发一条指定明文的 API 令牌（测试 / 迁移兼容）。
     * 正常用户令牌走 [UserTokenService.issue]（随机明文只返回一次）。
     */
    @Transactional
    fun createUserWithMembershipAndToken(
        username: String,
        displayName: String,
        rawToken: String,
        projectId: Long?,
        role: String,
    ): UserRecord {
        val user = createUserWithMembership(username, displayName, projectId, role)
        val userId = checkNotNull(user.id) { "用户创建后应回填 id" }
        userTokens.issue(
            userId = userId,
            name = "test",
            purpose = UserTokenService.PURPOSE_API,
            expiresAt = Instant.now().plus(java.time.Duration.ofDays(90)),
            rawToken = rawToken,
        )
        return user
    }

    /** 更新资料 / 密码（C3 / C4）。 */
    @Transactional
    fun update(
        id: Long,
        displayName: String? = null,
        email: String? = null,
        passwordHash: String? = null,
        status: String? = null,
        mustChangePassword: Boolean? = null,
        lastLoginAt: Instant? = null,
    ): UserRecord? {
        val entity = users.findById(id).orElse(null) ?: return null
        val saved =
            users.save(
                entity.copy(
                    displayName = displayName ?: entity.displayName,
                    email = if (email != null) normalizeEmail(email) else entity.email,
                    passwordHash = passwordHash ?: entity.passwordHash,
                    passwordUpdatedAt = if (passwordHash != null) Instant.now() else entity.passwordUpdatedAt,
                    status = status ?: entity.status,
                    mustChangePassword = mustChangePassword ?: entity.mustChangePassword,
                    lastLoginAt = lastLoginAt ?: entity.lastLoginAt,
                ),
            )
        return saved.toRecord()
    }

    /** 删除用户（先删团队/组织/项目成员关系，满足 FK RESTRICT；user_token / user_identity 由 FK CASCADE）。 */
    @Transactional
    fun deleteUser(id: Long) {
        teamMembers.deleteAllByUserId(id)
        orgMemberships.deleteAllByUserId(id)
        memberships.deleteAllByUserId(id)
        users.deleteById(id)
    }

    /** 设置/更新项目角色（唯一约束 + 先查后写，不依赖原生 upsert）。 */
    @Transactional
    fun setMembership(
        userId: Long,
        projectId: Long,
        role: String,
    ) {
        val existing = memberships.findByUserIdAndProjectId(userId, projectId)
        if (existing != null) {
            memberships.save(existing.copy(role = role))
        } else {
            memberships.save(MembershipEntity(userId = userId, projectId = projectId, role = role))
        }
    }

    fun membershipRole(
        userId: Long,
        projectId: Long,
    ): String? = memberships.findByUserIdAndProjectId(userId, projectId)?.role

    @Transactional
    fun removeMembership(
        userId: Long,
        projectId: Long,
    ) {
        memberships.deleteByUserIdAndProjectId(userId, projectId)
    }

    fun membershipsOf(userId: Long): Map<Long, String> = memberships.findAllByUserId(userId).associate { it.projectId to it.role }

    fun membersOfProject(projectId: Long): Map<Long, String> = memberships.findAllByProjectId(projectId).associate { it.userId to it.role }

    fun orgRole(userId: Long): String? = orgs.orgRole(userId, orgs.defaultOrgId())

    fun isOrgAdmin(userId: Long): Boolean = orgs.isOrgAdmin(userId, orgs.defaultOrgId())

    private fun normalizeEmail(email: String?): String? = email?.trim()?.lowercase()?.ifBlank { null }

    private fun UserEntity.toRecord(): UserRecord =
        UserRecord(
            id = id,
            username = username,
            displayName = displayName,
            email = email,
            status = status,
            hasPassword = !passwordHash.isNullOrBlank(),
            mustChangePassword = mustChangePassword,
            createdAt = createdAt.toString(),
        )
}
