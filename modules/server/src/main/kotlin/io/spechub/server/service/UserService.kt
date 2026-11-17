package io.spechub.server.service

import io.spechub.server.repository.MembershipEntity
import io.spechub.server.repository.MembershipRepository
import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserRepository
import io.spechub.spec.index.UserRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 用户/成员域 Service（Phase 2 首个迁入管理库的纵切）。
 *
 * - 管理库事务：多表写（用户 + 成员）用 Spring @Transactional，单库本地事务。
 * - changelog 仍在投影库（SQLite），跨库不做分布式事务：调用方按固定次序编排（选型文档 §4）。
 * - 对外仍返回 kernel `UserRecord`，web 层类型不变。
 */
@Service
class UserService(
    private val users: UserRepository,
    private val memberships: MembershipRepository,
) {
    companion object {
        /** bootstrap 平台 admin 用户名（由 spechub.admin-token 支撑） */
        const val ADMIN_USERNAME = "admin"
    }

    /** 启动时 bootstrap 平台 admin（幂等：存在则刷新 token hash 与平台 admin 标记） */
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
        return saved.toRecord()
    }

    fun findById(id: Long): UserRecord? = users.findById(id).orElse(null)?.toRecord()

    fun findByUsername(username: String): UserRecord? = users.findByUsername(username)?.toRecord()

    fun findByTokenHash(tokenHash: String): UserRecord? = users.findByTokenHash(tokenHash)?.toRecord()

    fun listUsers(): List<UserRecord> = users.findAllOrdered().map { it.toRecord() }

    /** 创建用户并加入项目（单事务；projectId 为空时只建用户） */
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
        if (projectId != null) {
            val userId = checkNotNull(saved.id) { "用户创建后应回填 id" }
            memberships.save(MembershipEntity(userId = userId, projectId = projectId, role = role))
        }
        return saved.toRecord()
    }

    /** 删除用户（先删成员关系，满足 FK RESTRICT；集合式删除，避免循环内逐条往返） */
    @Transactional
    fun deleteUser(id: Long) {
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

    /** 设置/更新项目角色（唯一约束 + 先查后写，不依赖原生 upsert） */
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

    fun membershipsOf(userId: Long): Map<Long, String> = memberships.findAllByUserId(userId).associate { it.projectId to it.role }

    fun membersOfProject(projectId: Long): Map<Long, String> = memberships.findAllByProjectId(projectId).associate { it.userId to it.role }

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
