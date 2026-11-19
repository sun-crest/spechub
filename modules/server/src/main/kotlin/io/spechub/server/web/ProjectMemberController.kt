package io.spechub.server.web

import io.spechub.server.repository.ProjectEntity
import io.spechub.server.service.ChangelogService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
 * 阶段 A —— 项目成员与角色（ADR §4.2）：
 * - GET    /api/projects/{projectId}/members          项目成员（项目成员可见）
 * - POST   /api/projects/{projectId}/members          添加已有组织用户（project admin+）
 * - PATCH  /api/projects/{projectId}/members/{userId} 改项目角色（project admin+；owner 走转移）
 * - DELETE /api/projects/{projectId}/members/{userId} 移除成员（project admin+；不能移除 owner）
 */
@RestController
@RequestMapping("/api/projects/{projectId}/members")
class ProjectMemberController(
    private val projects: ProjectService,
    private val users: UserService,
    private val orgs: OrgService,
    private val auth: AuthService,
    private val changelogService: ChangelogService,
) {
    data class MemberView(
        val userId: Long,
        val username: String,
        val displayName: String,
        val role: String,
        val orgRole: String? = null,
    )

    data class AddMemberRequest(
        val userId: Long? = null,
        val username: String? = null,
        val role: String = Role.VIEWER,
    )

    data class UpdateMemberRequest(
        val role: String,
    )

    @GetMapping
    fun list(
        @PathVariable projectId: Long,
        request: HttpServletRequest,
    ): List<MemberView> {
        auth.requireHuman(request, "查看项目成员")
        auth.requireProjectMember(request, projectId, "查看项目成员")
        val project = requireProject(projectId)
        val roles = users.membersOfProject(projectId)
        if (roles.isEmpty()) return emptyList()
        val userMap = users.findByIds(roles.keys)
        val orgRoles = orgs.membersOf(orgIdOf(project)).associate { it.userId to it.orgRole }
        return roles.entries
            .mapNotNull { (userId, role) ->
                userMap[userId]?.let { user ->
                    MemberView(
                        userId = userId,
                        username = user.username,
                        displayName = user.displayName,
                        role = role,
                        orgRole = orgRoles[userId],
                    )
                }
            }.sortedWith(compareByDescending<MemberView> { Role.rank(it.role) }.thenBy { it.username })
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable projectId: Long,
        @RequestBody @Valid body: AddMemberRequest,
        request: HttpServletRequest,
    ): MemberView {
        auth.requireHuman(request, "添加项目成员")
        auth.requireProjectRole(request, projectId, Role.ADMIN, "添加项目成员")
        val project = requireProject(projectId)
        validateMemberRole(body.role)
        val orgId = orgIdOf(project)
        val user =
            when {
                body.userId != null -> users.findById(body.userId)
                !body.username.isNullOrBlank() -> users.findByUsername(body.username.trim())
                else -> null
            } ?: throw ApiException(ApiErrorCode.VALIDATION, "userId 或 username 必填且用户存在")
        val userId = checkNotNull(user.id) { "user 必有 id" }
        if (orgs.membership(userId, orgId)?.status != OrgService.STATUS_ACTIVE) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, "用户 ${user.username} 不是组织成员")
        }
        if (users.membershipRole(userId, projectId) == Role.OWNER) {
            throw ApiException(ApiErrorCode.CONFLICT, "owner 角色不能通过成员接口修改")
        }
        users.setMembership(userId, projectId, body.role)
        changelog(project, request, "project.member.add", "添加项目成员 ${user.username}（${body.role}）")
        return toView(userId, body.role, orgId)
    }

    @PatchMapping("/{userId}")
    fun update(
        @PathVariable projectId: Long,
        @PathVariable userId: Long,
        @RequestBody @Valid body: UpdateMemberRequest,
        request: HttpServletRequest,
    ): MemberView {
        auth.requireHuman(request, "修改项目成员")
        auth.requireProjectRole(request, projectId, Role.ADMIN, "修改项目成员")
        val project = requireProject(projectId)
        validateMemberRole(body.role)
        val currentRole =
            users.membershipRole(userId, projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不是项目成员")
        if (currentRole == Role.OWNER) {
            throw ApiException(ApiErrorCode.CONFLICT, "owner 角色需先转移项目 owner")
        }
        val user = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        users.setMembership(userId, projectId, body.role)
        changelog(project, request, "project.member.role", "项目成员 ${user.username} → ${body.role}")
        return toView(userId, body.role, orgIdOf(project))
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable projectId: Long,
        @PathVariable userId: Long,
        request: HttpServletRequest,
    ) {
        auth.requireHuman(request, "移除项目成员")
        auth.requireProjectRole(request, projectId, Role.ADMIN, "移除项目成员")
        val project = requireProject(projectId)
        val currentRole =
            users.membershipRole(userId, projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不是项目成员")
        if (currentRole == Role.OWNER) {
            throw ApiException(ApiErrorCode.CONFLICT, "不能移除项目 owner，请先转移")
        }
        val user = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        users.removeMembership(userId, projectId)
        changelog(project, request, "project.member.delete", "移除项目成员 ${user.username}")
    }

    private fun requireProject(projectId: Long): ProjectEntity = projects.get(projectId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")

    private fun orgIdOf(project: ProjectEntity): Long = project.orgId ?: orgs.defaultOrgId()

    private fun validateMemberRole(role: String) {
        if (!Role.ALL.contains(role)) {
            throw ApiException(ApiErrorCode.VALIDATION, "role ∈ ${Role.ALL}")
        }
        if (role == Role.OWNER) {
            throw ApiException(ApiErrorCode.VALIDATION, "owner 只能通过转移接口设置")
        }
    }

    private fun toView(
        userId: Long,
        role: String,
        orgId: Long,
    ): MemberView {
        val user = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        return MemberView(
            userId = userId,
            username = user.username,
            displayName = user.displayName,
            role = role,
            orgRole = orgs.orgRole(userId, orgId),
        )
    }

    private fun changelog(
        project: ProjectEntity,
        request: HttpServletRequest,
        action: String,
        detail: String,
    ) {
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(request),
            action = action,
            detail = detail,
            orgId = orgIdOf(project),
        )
    }
}
