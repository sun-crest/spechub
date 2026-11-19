package io.spechub.server.service

import io.spechub.server.repository.MembershipEntity
import io.spechub.server.repository.MembershipRepository
import io.spechub.server.repository.OrgMembershipRepository
import io.spechub.server.repository.TeamMemberRepository
import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserRepository
import io.spechub.spec.index.UserRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 用户/成员域 Service（阶段 A：用户全局身份 + 组织成员关系 + 项目成员关系）。
 *
 * - 管理库事务：多表写（用户 + org_membership + membership）用 Spring @Transactional。
 * - `bootstrapAdmin` 同时确保默认组织的 org_admin 关系。
 * - changelog 跨库不做分布式事务：调用方按固定次序编排（选型文档 §4）。
 */
@Service
class UserService(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
    private val orgMemberships: OrgMembershipRepository,
    private val teamMembers: TeamMemberRepository,
    private val orgs: OrgService,
) {
    companion object {
        /** bootstrap 平台 admin 用户名（由 spechub.admin-token 支撑） */
        const val ADMIN_USERNAME = "admin"
    }

    /** 启动时 bootstrap 平台 admin（幂等：存在则刷新 token hash 与平台 admin 标记）。 */
    @Transactional
    fun bootstrapAdmin(tokenHash: String): UserRecord {
        val existing = users.findByUsername(ADMIN_USERNAME)
        val saved =
            if (existing != null) {
                users.save(existing.copy(tokenHash = tokenHash, isPlatformAdmin = true))
            } else {
                users.save(
                    UserEntity(
                        username = ADMIN_USERNAME,
                        displayName = ADMIN_USERNAME,
                        tokenHash = tokenHash,
                        isPlatformAdmin = true,
                        createdAt = Instant.now(),
                    ),
                )
            }
        val userId = checkNotNull(saved.id) { "用户创建/更新后应有 id" }
        orgs.ensureMembership(userId, orgs.defaultOrgId(), OrgService.ROLE_ADMIN)
        return saved.toRecord()
    }

    fun findById(id: Long): UserRecord? = users.findById(id).orElse(null)?.toRecord()

    fun findByUsername(username: String): UserRecord? = users.findByUsername(username)?.toRecord()

    fun findByTokenHash(tokenHash: String): UserRecord? = users.findByTokenHash(tokenHash)?.toRecord()

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
        tokenHash: String,
        orgRole: String,
    ): UserRecord {
        val saved =
            users.save(
                UserEntity(
                    username = username,
                    displayName = displayName.ifBlank { username },
                    tokenHash = tokenHash,
                    isPlatformAdmin = false,
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
        tokenHash: String,
        projectId: Long?,
        role: String,
    ): UserRecord {
        val saved =
            users.save(
                UserEntity(
                    username = username,
                    displayName = displayName.ifBlank { username },
                    tokenHash = tokenHash,
                    isPlatformAdmin = false,
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

    /** 删除用户（先删团队/组织/项目成员关系，满足 FK RESTRICT；集合式删除）。 */
    @Transactional
    fun deleteUser(id: Long) {
        teamMembers.deleteAllByUserId(id)
        orgMemberships.deleteAllByUserId(id)
        memberships.deleteAllByUserId(id)
        users.deleteById(id)
    }

    fun updateToken(
        id: Long,
        newHash: String,
    ) {
        val user = users.findById(id).orElse(null) ?: return
        users.save(user.copy(tokenHash = newHash))
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

    private fun UserEntity.toRecord(): UserRecord =
        UserRecord(
            id = id,
            username = username,
            displayName = displayName,
            tokenHash = tokenHash,
            isPlatformAdmin = isPlatformAdmin,
            createdAt = createdAt.toString(),
        )
}
