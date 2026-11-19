package io.spechub.server.service

import io.spechub.server.repository.SubscriptionRepository
import io.spechub.server.repository.TeamEntity
import io.spechub.server.repository.TeamMemberEntity
import io.spechub.server.repository.TeamMemberRepository
import io.spechub.server.repository.TeamRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 团队域 Service（阶段 B）：组织级团队 CRUD、成员与角色、旧订阅文本解析。
 *
 * - 团队名在组织内唯一（忽略大小写与首尾空白）。
 * - 团队必须始终保留至少一个 lead；`org_admin` 不在成员表内，但权限上视同 lead。
 * - 删除团队前必须没有订阅（FK RESTRICT + Service 前置校验）；通知历史保留（team_id SET NULL）。
 * - 管理动作写 changelog（team.*，repo_id 为空、带 org_id）。
 */
@Service
class TeamService(
    private val teams: TeamRepository,
    private val members: TeamMemberRepository,
    private val subscriptions: SubscriptionRepository,
    private val orgs: OrgService,
    private val changelogService: ChangelogService,
) {
    companion object {
        const val ROLE_LEAD = "lead"
        const val ROLE_MEMBER = "member"
        val ROLES = setOf(ROLE_LEAD, ROLE_MEMBER)
        const val MAX_NAME_LENGTH = 128
    }

    data class TeamMembership(
        val team: TeamEntity,
        val role: String,
    )

    fun get(
        id: Long,
        orgId: Long,
    ): TeamEntity? = teams.findByIdAndOrgId(id, orgId)

    fun list(orgId: Long): List<TeamEntity> = teams.findAllByOrgIdOrderByNameAsc(orgId)

    fun findByName(
        orgId: Long,
        name: String,
    ): TeamEntity? = teams.findByOrgIdAndNameIgnoreCase(orgId, name.trim())

    fun roleInTeam(
        userId: Long,
        teamId: Long,
    ): String? = members.findByTeamIdAndUserId(teamId, userId)?.role

    fun isLead(
        userId: Long,
        teamId: Long,
    ): Boolean = roleInTeam(userId, teamId) == ROLE_LEAD

    fun teamsOf(userId: Long): List<TeamMembership> {
        val rows = members.findAllByUserId(userId)
        if (rows.isEmpty()) return emptyList()
        val byId = teams.findAllById(rows.map { it.teamId }.distinct()).associateBy { checkNotNull(it.id) { "team 必有 id" } }
        return rows
            .mapNotNull { row -> byId[row.teamId]?.let { TeamMembership(it, row.role) } }
            .sortedBy { it.team.name }
    }

    fun membersOf(teamId: Long): List<TeamMemberEntity> = members.findAllByTeamIdOrderByRoleAscUserIdAsc(teamId)

    fun memberCount(teamId: Long): Long = members.countByTeamId(teamId)

    fun subscriptionCount(teamId: Long): Long = subscriptions.countByTeamId(teamId)

    @Transactional
    fun create(
        orgId: Long,
        name: String,
        description: String = "",
        actor: String = "system",
    ): TeamEntity {
        val normalized = validateName(name)
        if (teams.findByOrgIdAndNameIgnoreCase(orgId, normalized) != null) {
            throw IllegalArgumentException("团队名已存在：$normalized")
        }
        val saved =
            teams.save(
                TeamEntity(
                    orgId = orgId,
                    name = normalized,
                    description = description.trim(),
                    createdAt = Instant.now(),
                ),
            )
        changelogService.add(null, null, actor, "team.create", "创建团队 $normalized", orgId)
        return saved
    }

    @Transactional
    fun update(
        id: Long,
        orgId: Long,
        name: String? = null,
        description: String? = null,
        actor: String = "system",
    ): TeamEntity {
        val team = get(id, orgId) ?: throw IllegalArgumentException("团队 $id 不存在")
        val newName = name?.let(::validateName) ?: team.name
        val conflict = teams.findByOrgIdAndNameIgnoreCase(orgId, newName)
        if (conflict != null && conflict.id != team.id) {
            throw IllegalArgumentException("团队名已存在：$newName")
        }
        val saved = teams.save(team.copy(name = newName, description = description?.trim() ?: team.description))
        changelogService.add(null, null, actor, "team.update", "更新团队 ${saved.name}", orgId)
        return saved
    }

    @Transactional
    fun delete(
        id: Long,
        orgId: Long,
        actor: String = "system",
    ) {
        val team = get(id, orgId) ?: throw IllegalArgumentException("团队 $id 不存在")
        if (subscriptions.countByTeamId(id) > 0) {
            throw IllegalStateException("团队仍有订阅，请先取消订阅")
        }
        members.deleteAllByTeamId(id)
        teams.deleteById(id)
        changelogService.add(null, null, actor, "team.delete", "删除团队 ${team.name}", orgId)
    }

    @Transactional
    fun addMember(
        teamId: Long,
        orgId: Long,
        userId: Long,
        role: String = ROLE_MEMBER,
        actor: String = "system",
    ): TeamMemberEntity {
        validateRole(role)
        val team = get(teamId, orgId) ?: throw IllegalArgumentException("团队 $teamId 不存在")
        requireOrgMember(userId, orgId)
        val existing = members.findByTeamIdAndUserId(teamId, userId)
        if (existing != null && existing.role == ROLE_LEAD && role != ROLE_LEAD) {
            requireAnotherLead(teamId)
        }
        val saved =
            if (existing != null) {
                members.save(existing.copy(role = role))
            } else {
                members.save(
                    TeamMemberEntity(
                        teamId = teamId,
                        userId = userId,
                        role = role,
                        createdAt = Instant.now(),
                    ),
                )
            }
        val action = if (existing == null) "team.member.add" else "team.member.role"
        changelogService.add(null, null, actor, action, "团队 ${team.name} 成员 $userId → $role", orgId)
        return saved
    }

    @Transactional
    fun removeMember(
        teamId: Long,
        orgId: Long,
        userId: Long,
        actor: String = "system",
    ) {
        val team = get(teamId, orgId) ?: throw IllegalArgumentException("团队 $teamId 不存在")
        val existing =
            members.findByTeamIdAndUserId(teamId, userId)
                ?: throw IllegalArgumentException("用户 $userId 不是团队成员")
        if (existing.role == ROLE_LEAD) requireAnotherLead(teamId)
        members.delete(existing)
        changelogService.add(null, null, actor, "team.member.delete", "移除团队 ${team.name} 成员 $userId", orgId)
    }

    /**
     * 旧订阅文本 → 团队（B2/B4 过渡）：不存在则创建。
     * 并发创建同名团队由数据库唯一键兜底（后到请求失败），不做静默重试——
     * PostgreSQL 在唯一键冲突后事务已 aborted，事务内重查不可用。
     */
    @Transactional
    fun resolveOrCreate(
        orgId: Long,
        name: String,
    ): TeamEntity {
        val normalized = validateName(name)
        teams.findByOrgIdAndNameIgnoreCase(orgId, normalized)?.let { return it }
        return create(orgId, normalized, actor = "system")
    }

    private fun validateName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "团队名不能为空" }
        require(normalized.length <= MAX_NAME_LENGTH) { "团队名最长 $MAX_NAME_LENGTH 字符" }
        return normalized
    }

    private fun validateRole(role: String) {
        require(role in ROLES) { "团队角色 ∈ $ROLES" }
    }

    private fun requireOrgMember(
        userId: Long,
        orgId: Long,
    ) {
        require(orgs.membership(userId, orgId)?.status == OrgService.STATUS_ACTIVE) { "用户 $userId 不是组织成员" }
    }

    /** 调用前已确认目标成员是 lead；只剩一个 lead 时禁止降级/移除。 */
    private fun requireAnotherLead(teamId: Long) {
        if (members.countByTeamIdAndRole(teamId, ROLE_LEAD) <= 1) {
            throw IllegalStateException("团队至少保留一个 lead")
        }
    }
}
