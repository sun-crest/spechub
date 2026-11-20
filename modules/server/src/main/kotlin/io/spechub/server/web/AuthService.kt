package io.spechub.server.web

import io.spechub.server.repository.ProjectEntity
import io.spechub.server.repository.UserStatus
import io.spechub.server.security.AppUserPrincipal
import io.spechub.server.security.OidcAppUserPrincipal
import io.spechub.server.security.ServicePrincipal
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/*
 * 阶段 C —— RBAC 认证与域授权（ADR §4.2 / §4.3 / §6.1 / §7）。
 *
 * 身份由 Spring Security 解析：
 * - Web 链：Spring Session 中的 AppUserPrincipal；
 * - Bearer 链：AppUserPrincipal（用户令牌）或 ServicePrincipal（服务账号）。
 *
 * 域授权仍集中在本服务：每次请求按库内 membership / org_membership 判定，
 * 不把 org / project 角色写进 authorities，保证角色变更立即生效。
 * 服务账号只在其 projectId 内有效；跨项目读 404 / 写 403，绝不退化为全量。
 */
@Service
class AuthService(
    private val repoService: RepoService,
    private val projectService: ProjectService,
    private val users: UserService,
    private val orgs: OrgService,
    private val teams: TeamService,
) {
    companion object {
        /** 平台 admin 用户名（与 UserService.ADMIN_USERNAME 一致） */
        const val ADMIN_USERNAME = UserService.ADMIN_USERNAME
        const val SERVICE_ACTOR_PREFIX = "service:"
    }

    // ── 当前身份 ─────────────────────────────────────────────────────────

    fun currentAuthentication(): Authentication? =
        SecurityContextHolder.getContext().authentication?.takeIf {
            it.isAuthenticated && it !is AnonymousAuthenticationToken
        }

    /** 服务账号 principal（用户 / 匿名返回 null）。 */
    fun currentService(): ServicePrincipal? = currentAuthentication()?.principal as? ServicePrincipal

    /** 人类用户（密码 / OIDC / 服务账号中的服务账号返回 null）；每请求校验 status，禁用立即 401。 */
    fun currentUser(req: HttpServletRequest? = null): UserRecord? {
        val userId = humanUserId(currentAuthentication()?.principal) ?: return null
        val user =
            users.findById(userId)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "账号不存在或已被删除")
        if (user.status !in UserStatus.LOGINNABLE) {
            throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "账号已禁用")
        }
        return user
    }

    /** 人类用户 id（服务账号 / 匿名返回 null）。 */
    fun currentUserId(): Long? = humanUserId(currentAuthentication()?.principal)

    /** 当前组织：服务账号取账号 org；人类取第一个 active org_membership。 */
    fun currentOrg(req: HttpServletRequest? = null): io.spechub.server.repository.OrgEntity {
        currentService()?.let { service ->
            return orgs.get(service.orgId) ?: orgs.defaultOrg()
        }
        val user = currentUser(req) ?: return orgs.defaultOrg()
        return orgForUser(user)
    }

    fun currentOrgId(req: HttpServletRequest? = null): Long = checkNotNull(currentOrg(req).id) { "org 必有 id" }

    /** 用户当前组织：第一个 active 的 org_membership，兜底默认组织。 */
    fun orgForUser(user: UserRecord): io.spechub.server.repository.OrgEntity {
        val uid = user.id ?: return orgs.defaultOrg()
        val membership = orgs.membershipsOf(uid).firstOrNull { it.status == OrgService.STATUS_ACTIVE }
        return membership?.let { orgs.get(it.orgId) } ?: orgs.defaultOrg()
    }

    /** 审计 / changelog 操作者：用户名或 `service:<name>`；无身份回退 anonymous。 */
    fun currentActor(req: HttpServletRequest? = null): String =
        currentService()?.let { SERVICE_ACTOR_PREFIX + it.name }
            ?: humanUsername(currentAuthentication()?.principal)
            ?: "anonymous"

    // ── 组织 / 项目角色 ──────────────────────────────────────────────────

    fun orgRole(
        user: UserRecord,
        orgId: Long,
    ): String? = user.id?.let { orgs.orgRole(it, orgId) }

    fun isOrgAdmin(
        user: UserRecord,
        orgId: Long,
    ): Boolean = user.id?.let { orgs.isOrgAdmin(it, orgId) } ?: false

    fun isOrgAdminById(
        userId: Long,
        orgId: Long,
    ): Boolean = orgs.isOrgAdmin(userId, orgId)

    /** 人类用户对项目的有效角色：org_admin 恒 owner；其余按 membership。 */
    fun effectiveRole(
        user: UserRecord,
        projectId: Long?,
    ): String? {
        val uid = user.id ?: return null
        val project = projectId?.let { projectService.get(it) } ?: return null
        val orgId = project.orgId ?: orgs.defaultOrgId()
        if (orgs.isOrgAdmin(uid, orgId)) return Role.OWNER
        return projectId.let { users.membershipRole(uid, it) }
    }

    /** 人 / 服务账号统一有效角色；服务账号只在其 projectId 返回自身角色。 */
    fun effectiveRoleFor(
        principal: Any?,
        projectId: Long?,
    ): String? =
        when (principal) {
            is ServicePrincipal -> if (projectId != null && principal.projectId == projectId) principal.role else null
            is AppUserPrincipal -> users.findById(principal.userId)?.let { effectiveRole(it, projectId) }
            is OidcAppUserPrincipal -> users.findById(principal.appUserId)?.let { effectiveRole(it, projectId) }
            else -> null
        }

    fun roleForRepo(
        req: HttpServletRequest,
        repoId: Long,
    ): String? = effectiveRoleFor(currentAuthentication()?.principal, repoService.getRepo(repoId)?.projectId)

    /** 项目可读判定（人类）：成员可读 draft/release；同组织非成员仅 internal/public release；匿名仅 public release。 */
    fun canReadProject(
        user: UserRecord?,
        project: ProjectEntity,
        stage: String,
    ): Boolean {
        if (user == null) {
            return project.visibility == ProjectService.VISIBILITY_PUBLIC && stage == Stage.RELEASE
        }
        val uid = user.id ?: return false
        val orgId = project.orgId ?: orgs.defaultOrgId()
        if (orgs.isOrgAdmin(uid, orgId)) return true
        val projectId = project.id ?: return false
        if (users.membershipRole(uid, projectId) != null) return true
        val orgMember = orgs.membership(uid, orgId)?.status == OrgService.STATUS_ACTIVE
        return orgMember &&
            (project.visibility == ProjectService.VISIBILITY_INTERNAL || project.visibility == ProjectService.VISIBILITY_PUBLIC) &&
            stage == Stage.RELEASE
    }

    /** 当前身份是否可读项目：服务账号只认自身项目。 */
    fun canReadProject(
        project: ProjectEntity,
        stage: String,
    ): Boolean {
        currentService()?.let { return project.id == it.projectId }
        return canReadProject(currentUser(), project, stage)
    }

    /** 可管理项目：org_admin 或项目 admin+。 */
    fun canManageProject(
        user: UserRecord,
        project: ProjectEntity,
    ): Boolean = Role.rank(effectiveRole(user, project.id)) >= Role.rank(Role.ADMIN)

    // ── 可见集合（列表 / 搜索 / 导出） ────────────────────────────────────

    fun visibleProjects(req: HttpServletRequest? = null): List<ProjectEntity> {
        currentService()?.let { service ->
            return projectService.get(service.projectId)?.let { listOf(it) } ?: emptyList()
        }
        val projects = projectService.list(currentOrgId(req))
        val user = currentUser(req)
        return projects.filter { canReadProject(user, it, Stage.RELEASE) }
    }

    fun visibleProjectIds(
        req: HttpServletRequest,
        stage: String,
    ): Set<Long> {
        currentService()?.let { return setOf(it.projectId) }
        val projects = projectService.list(currentOrgId(req))
        val user = currentUser(req)
        return projects.filter { canReadProject(user, it, stage) }.mapNotNull { it.id }.toSet()
    }

    /** 指定 stage 下可见 repo id；调用方必须显式传入，禁止为空时退化为全量。 */
    fun visibleRepoIds(
        req: HttpServletRequest,
        stage: String,
    ): Set<Long> {
        currentService()?.let { service ->
            return repoService.listReposByProjectIds(listOf(service.projectId)).map { it.id }.toSet()
        }
        val projectIds = visibleProjectIds(req, stage)
        return if (projectIds.isEmpty()) {
            emptySet()
        } else {
            repoService.listReposByProjectIds(projectIds).map { it.id }.toSet()
        }
    }

    /** 指定项目下的可见 repo id；项目不可见统一 404。 */
    fun visibleRepoIds(
        req: HttpServletRequest,
        stage: String,
        projectId: Long,
    ): Set<Long> {
        requireProjectVisible(req, projectId, stage)
        val inProject = repoService.listReposByProjectIds(listOf(projectId)).map { it.id }.toSet()
        return inProject intersect visibleRepoIds(req, stage)
    }

    /** 当前身份可进入内部视图（评审 / 评论 / 变更记录）的项目 id。 */
    fun memberProjectIds(req: HttpServletRequest): Set<Long> {
        currentService()?.let { return setOf(it.projectId) }
        val user = currentUser(req) ?: return emptySet()
        val uid = user.id ?: return emptySet()
        val orgId = currentOrgId(req)
        val projects = projectService.list(orgId)
        if (orgs.isOrgAdmin(uid, orgId)) return projects.mapNotNull { it.id }.toSet()
        return projects
            .filter { project -> project.id?.let { users.membershipRole(uid, it) != null } == true }
            .mapNotNull { it.id }
            .toSet()
    }

    fun memberRepoIds(req: HttpServletRequest): Set<Long> {
        val projectIds = memberProjectIds(req)
        return if (projectIds.isEmpty()) {
            emptySet()
        } else {
            repoService.listReposByProjectIds(projectIds).map { it.id }.toSet()
        }
    }

    // ── require：认证 / 人类 / 组织 ───────────────────────────────────────

    /** 人类登录用户；服务账号拒绝（评论 / 订阅 / 团队 / 评审审批等协作操作）。 */
    fun requireHuman(
        req: HttpServletRequest,
        op: String,
    ): UserRecord {
        if (currentService() != null) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 仅限人类用户（服务账号不可用）")
        }
        return currentUser(req) ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
    }

    /** 任一已认证身份：人类或服务账号。 */
    fun requireAuthenticated(
        req: HttpServletRequest,
        op: String,
    ): UserRecord {
        currentService()?.let { return it.toSyntheticUser() }
        return currentUser(req) ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
    }

    /** 组织级管理：仅人类 org_admin（服务账号 403）。 */
    fun requireOrgAdmin(
        req: HttpServletRequest,
        op: String,
    ): UserRecord {
        if (currentService() != null) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要组织管理员（服务账号不可用）")
        }
        val user =
            currentUser(req)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val orgId = currentOrgId(req)
        if (isOrgAdmin(user, orgId)) return user
        throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要组织管理员")
    }

    // ── require：项目 / 仓库 ──────────────────────────────────────────────

    /** 内部视图入口：项目成员或 org_admin；服务账号仅限自身项目；不可见统一 404。 */
    fun requireProjectMember(
        req: HttpServletRequest,
        projectId: Long,
        op: String,
    ): UserRecord {
        currentService()?.let { service ->
            if (service.projectId != projectId) throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
            return service.toSyntheticUser()
        }
        val user =
            currentUser(req)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val project =
            projectService.get(projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        val uid = user.id ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val orgId = project.orgId ?: orgs.defaultOrgId()
        if (orgs.isOrgAdmin(uid, orgId) || users.membershipRole(uid, projectId) != null) return user
        throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
    }

    fun requireRepoMember(
        req: HttpServletRequest,
        repoId: Long,
        op: String,
    ): UserRecord {
        val repo =
            repoService.getRepo(repoId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        return requireProjectMember(req, repo.projectId, op)
    }

    /** 读端点入口：项目不存在或不可见统一 404。 */
    fun requireProjectVisible(
        req: HttpServletRequest,
        projectId: Long,
        stage: String = Stage.RELEASE,
    ): ProjectEntity {
        val project =
            projectService.get(projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        currentService()?.let { service ->
            if (service.projectId != projectId) throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
            return project
        }
        if (!canReadProject(currentUser(req), project, stage)) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        }
        return project
    }

    fun requireRepoVisible(
        req: HttpServletRequest,
        repoId: Long,
        stage: String = Stage.RELEASE,
    ) = run {
        val repo =
            repoService.getRepo(repoId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        requireProjectVisible(req, repo.projectId, stage)
        repo
    }

    /** 写端点入口：人类按项目角色；服务账号仅自身项目，跨项目 404、角色不足 403。 */
    fun requireRepoRole(
        req: HttpServletRequest,
        repoId: Long,
        min: String,
        op: String,
    ) {
        val repo =
            repoService.getRepo(repoId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        requireProjectRole(req, repo.projectId, min, op)
    }

    fun requireProjectRole(
        req: HttpServletRequest,
        projectId: Long?,
        min: String,
        op: String,
    ) {
        currentService()?.let { service ->
            if (projectId == null || service.projectId != projectId) {
                throw ApiException(ApiErrorCode.NOT_FOUND, "project ${projectId ?: "-"} 不存在")
            }
            if (Role.rank(service.role) < Role.rank(min)) {
                throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要 $min 及以上角色（服务账号当前 ${service.role}）")
            }
            return
        }
        val user =
            currentUser(req)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val role = effectiveRole(user, projectId)
        if (role == null || Role.rank(role) < Role.rank(min)) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要 $min 及以上角色（当前 ${role ?: "无项目权限"}）")
        }
    }

    fun requireAdmin(
        req: HttpServletRequest,
        repoId: Long,
        op: String,
    ) = requireRepoRole(req, repoId, Role.ADMIN, op)

    // ── require：团队（仅人类） ───────────────────────────────────────────

    fun requireTeam(
        req: HttpServletRequest,
        teamId: Long,
        op: String,
    ): io.spechub.server.repository.TeamEntity {
        if (currentService() != null) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 仅限人类用户（服务账号不可用）")
        }
        return teams.get(teamId, currentOrgId(req))
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "team $teamId 不存在（$op）")
    }

    fun requireTeamMember(
        req: HttpServletRequest,
        teamId: Long,
        op: String,
    ): UserRecord {
        val team = requireTeam(req, teamId, op)
        val user = requireHuman(req, op)
        val uid = user.id ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        if (isOrgAdmin(user, team.orgId)) return user
        if (teams.roleInTeam(uid, teamId) == null) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "team $teamId 不存在（$op）")
        }
        return user
    }

    fun requireTeamLead(
        req: HttpServletRequest,
        teamId: Long,
        op: String,
    ): UserRecord {
        val team = requireTeam(req, teamId, op)
        val user = requireHuman(req, op)
        val uid = user.id ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        if (isOrgAdmin(user, team.orgId)) return user
        if (teams.roleInTeam(uid, teamId) != TeamService.ROLE_LEAD) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要团队 lead")
        }
        return user
    }

    private fun ServicePrincipal.toSyntheticUser(): UserRecord =
        UserRecord(
            username = SERVICE_ACTOR_PREFIX + name,
            displayName = name,
            status = "active",
        )

    private fun humanUserId(principal: Any?): Long? =
        when (principal) {
            is AppUserPrincipal -> principal.userId
            is OidcAppUserPrincipal -> principal.appUserId
            else -> null
        }

    private fun humanUsername(principal: Any?): String? =
        when (principal) {
            is AppUserPrincipal -> principal.username
            is OidcAppUserPrincipal -> principal.username
            else -> null
        }
}
