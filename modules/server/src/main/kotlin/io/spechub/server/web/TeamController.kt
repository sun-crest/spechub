package io.spechub.server.web

import io.spechub.server.repository.TeamEntity
import io.spechub.server.service.OrgService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * 阶段 B（B3）—— 团队与团队成员（ADR §4.2 / §8.1）：
 * - GET    /api/teams                         组织成员可见的团队列表（附 myRole / canManage）
 * - POST   /api/teams                         org_admin 创建；创建者加入为 lead
 * - GET    /api/teams/{id}                    团队详情（组织成员）
 * - PATCH  /api/teams/{id}                    org_admin 或 team lead 改名/改描述
 * - DELETE /api/teams/{id}                    org_admin 删除；有订阅 409
 * - GET    /api/teams/{id}/members            团队成员或 org_admin（其他 404）
 * - POST   /api/teams/{id}/members            team lead / org_admin 添加组织用户
 * - PATCH  /api/teams/{id}/members/{userId}   team lead / org_admin 改 lead / member
 * - DELETE /api/teams/{id}/members/{userId}   team lead / org_admin 移除；最后一个 lead 409
 *
 * 团队不存在 / 跨组织统一 404；非 lead 403；重名与最后 lead 冲突 409。
 */
@RestController
@RequestMapping("/api/teams")
class TeamController(
    private val teams: TeamService,
    private val users: UserService,
    private val orgs: OrgService,
    private val auth: AuthService,
) {
    data class TeamView(
        val id: Long,
        val name: String,
        val description: String,
        val memberCount: Long,
        val subscriptionCount: Long,
        val myRole: String? = null,
        /** org_admin 或 team lead；dev 免登录放行 */
        val canManage: Boolean = false,
        val createdAt: String? = null,
    )

    data class TeamMemberView(
        val userId: Long,
        val username: String,
        val displayName: String,
        val role: String,
        val orgRole: String? = null,
    )

    data class CreateTeamRequest(
        @field:NotBlank val name: String,
        val description: String = "",
    )

    data class UpdateTeamRequest(
        val name: String? = null,
        val description: String? = null,
    )

    data class AddTeamMemberRequest(
        val userId: Long? = null,
        val username: String? = null,
        val role: String = TeamService.ROLE_MEMBER,
    )

    data class UpdateTeamMemberRequest(
        @field:NotBlank val role: String,
    )

    @GetMapping
    fun list(request: HttpServletRequest): List<TeamView> {
        val user = auth.requireAuthenticated(request, "查看团队")
        val orgId = auth.currentOrgId(request)
        return teams.list(orgId).map { team ->
            val id = checkNotNull(team.id) { "team 必有 id" }
            val role = myRole(user, id)
            toView(team, role, canManage(user, orgId, role))
        }
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): TeamView {
        val user = auth.requireAuthenticated(request, "查看团队")
        val team = auth.requireTeam(request, id, "查看团队")
        val role = myRole(user, id)
        return toView(team, role, canManage(user, team.orgId, role))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody @Valid body: CreateTeamRequest,
        request: HttpServletRequest,
    ): TeamView {
        val user = auth.requireOrgAdmin(request, "创建团队")
        val orgId = auth.currentOrgId(request)
        val actor = auth.currentActor(request)
        val team = mapErrors { teams.create(orgId, body.name, body.description, actor) }
        val teamId = checkNotNull(team.id) { "team 必有 id" }
        val uid = user.id?.takeIf { it > 0 }
        val role =
            if (uid != null) {
                mapErrors { teams.addMember(teamId, orgId, uid, TeamService.ROLE_LEAD, actor) }
                TeamService.ROLE_LEAD
            } else {
                null
            }
        return toView(team, role, true)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: UpdateTeamRequest,
        request: HttpServletRequest,
    ): TeamView {
        val user = auth.requireTeamLead(request, id, "修改团队")
        val orgId = auth.currentOrgId(request)
        val team = mapErrors { teams.update(id, orgId, body.name, body.description, auth.currentActor(request)) }
        return toView(team, myRole(user, id), true)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        auth.requireTeam(request, id, "删除团队")
        auth.requireOrgAdmin(request, "删除团队")
        mapErrors { teams.delete(id, auth.currentOrgId(request), auth.currentActor(request)) }
    }

    @GetMapping("/{id}/members")
    fun members(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): List<TeamMemberView> {
        auth.requireTeamMember(request, id, "查看团队成员")
        val orgId = auth.currentOrgId(request)
        val rows = teams.membersOf(id)
        if (rows.isEmpty()) return emptyList()
        val userMap = users.findByIds(rows.map { it.userId })
        val orgRoles = orgs.membersOf(orgId).associate { it.userId to it.orgRole }
        return rows.mapNotNull { row ->
            userMap[row.userId]?.let { user ->
                TeamMemberView(
                    userId = row.userId,
                    username = user.username,
                    displayName = user.displayName,
                    role = row.role,
                    orgRole = orgRoles[row.userId],
                )
            }
        }
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @PathVariable id: Long,
        @RequestBody @Valid body: AddTeamMemberRequest,
        request: HttpServletRequest,
    ): TeamMemberView {
        auth.requireTeamLead(request, id, "添加团队成员")
        val orgId = auth.currentOrgId(request)
        val user = resolveUser(body.userId, body.username)
        val userId = checkNotNull(user.id) { "user 必有 id" }
        val saved = mapErrors { teams.addMember(id, orgId, userId, body.role, auth.currentActor(request)) }
        return toMemberView(userId, saved.role, orgId)
    }

    @PatchMapping("/{id}/members/{userId}")
    fun updateMember(
        @PathVariable id: Long,
        @PathVariable userId: Long,
        @RequestBody @Valid body: UpdateTeamMemberRequest,
        request: HttpServletRequest,
    ): TeamMemberView {
        auth.requireTeamLead(request, id, "修改团队成员")
        val orgId = auth.currentOrgId(request)
        if (teams.roleInTeam(userId, id) == null) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不是团队成员")
        }
        val saved = mapErrors { teams.addMember(id, orgId, userId, body.role, auth.currentActor(request)) }
        return toMemberView(userId, saved.role, orgId)
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(
        @PathVariable id: Long,
        @PathVariable userId: Long,
        request: HttpServletRequest,
    ) {
        auth.requireTeamLead(request, id, "移除团队成员")
        mapErrors { teams.removeMember(id, auth.currentOrgId(request), userId, auth.currentActor(request)) }
    }

    private fun myRole(
        user: UserRecord,
        teamId: Long,
    ): String? = user.id?.takeIf { it > 0 }?.let { teams.roleInTeam(it, teamId) }

    private fun canManage(
        user: UserRecord,
        orgId: Long,
        role: String?,
    ): Boolean = !auth.authEnabled || auth.isOrgAdmin(user, orgId) || role == TeamService.ROLE_LEAD

    private fun toView(
        team: TeamEntity,
        myRole: String?,
        canManage: Boolean,
    ): TeamView {
        val id = checkNotNull(team.id) { "team 必有 id" }
        return TeamView(
            id = id,
            name = team.name,
            description = team.description,
            memberCount = teams.memberCount(id),
            subscriptionCount = teams.subscriptionCount(id),
            myRole = myRole,
            canManage = canManage,
            createdAt = team.createdAt.toString(),
        )
    }

    private fun resolveUser(
        userId: Long?,
        username: String?,
    ): UserRecord =
        when {
            userId != null -> users.findById(userId)
            !username.isNullOrBlank() -> users.findByUsername(username.trim())
            else -> null
        } ?: throw ApiException(ApiErrorCode.VALIDATION, "userId 或 username 必填且用户存在")

    private fun toMemberView(
        userId: Long,
        role: String,
        orgId: Long,
    ): TeamMemberView {
        val user = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        return TeamMemberView(
            userId = userId,
            username = user.username,
            displayName = user.displayName,
            role = role,
            orgRole = orgs.orgRole(userId, orgId),
        )
    }

    private fun <T> mapErrors(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, e.message ?: "参数不合法")
        } catch (e: IllegalStateException) {
            throw ApiException(ApiErrorCode.CONFLICT, e.message ?: "状态冲突")
        }
}
