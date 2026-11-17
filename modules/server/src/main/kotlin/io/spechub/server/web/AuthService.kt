package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.repository.OrgEntity
import io.spechub.server.repository.ProjectEntity
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * I4 / 阶段 A —— RBAC 认证与权限判定（ADR §4.2 / §4.3 / §7）。
 *
 * 模型：
 * - 用户 = `app_user`（全局身份，token 只存 SHA-256 hash）。
 * - 组织 = `org_membership`（org_admin / org_member）；阶段 A 只有默认组织。
 * - 项目 = `membership`（owner/admin/editor/viewer）。
 * - 可见性 = `project.visibility`（private/internal/public）+ stage（draft/release）。
 * - 未配置 admin-token → 不启用鉴权：全放行（本地开发 / 最小部署）。
 *
 * 角色优先级：平台 admin → org_admin → 项目 membership。
 * 组织管理员在该组织所有项目上视为 owner（阶段 A 的兼容策略）。
 */
@Service
class AuthService(
    private val repoService: RepoService,
    private val projectService: ProjectService,
    private val users: UserService,
    private val orgs: OrgService,
    private val props: SpecHubProperties,
) {
    /** 请求属性名：鉴权后放当前用户 / 当前组织 */
    companion object {
        const val REQ_USER = "spechub.auth.user"
        const val REQ_USERNAME = "spechub.auth.username"
        const val REQ_ORG = "spechub.auth.org"

        /** 平台 admin 用户名（与 UserService.ADMIN_USERNAME 一致） */
        const val ADMIN_USERNAME = UserService.ADMIN_USERNAME

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    val authEnabled: Boolean get() = props.adminToken != null

    /** 启动时 bootstrap 平台 admin（幂等；token_hash 由 admin-token 派生） */
    fun bootstrapAdmin(): UserRecord? {
        val token = props.adminToken ?: return null
        return users.bootstrapAdmin(sha256(token))
    }

    /** Bearer → 当前用户（未启用鉴权返回 null 表示「无用户但已放行」） */
    fun userForToken(token: String?): UserRecord? {
        if (token == null || token.isBlank()) return null
        return users.findByTokenHash(sha256(token))
    }

    /** request 上由 TokenAuthFilter 填充的当前用户 */
    fun currentUser(req: HttpServletRequest): UserRecord? = req.getAttribute(REQ_USER) as? UserRecord

    /** request 上的当前组织（阶段 A 为默认组织；filter 命中用户时预填） */
    fun currentOrg(req: HttpServletRequest): OrgEntity = req.getAttribute(REQ_ORG) as? OrgEntity ?: orgs.defaultOrg()

    fun currentOrgId(req: HttpServletRequest): Long = checkNotNull(currentOrg(req).id) { "org 必有 id" }

    /** 用户当前组织：阶段 A 取第一个 active 的 org_membership，兜底默认组织。 */
    fun orgForUser(user: UserRecord): OrgEntity {
        val uid = user.id ?: return orgs.defaultOrg()
        val membership =
            orgs
                .membershipsOf(uid)
                .firstOrNull { it.status == OrgService.STATUS_ACTIVE }
        return membership?.let { orgs.get(it.orgId) } ?: orgs.defaultOrg()
    }

    /** request 上的操作者用户名（filter 已填充）；无则回退 anonymous */
    fun currentActor(req: HttpServletRequest): String = req.getAttribute(REQ_USERNAME) as? String ?: "anonymous"

    /** 组织级角色（平台 admin 恒 org_admin）。 */
    fun orgRole(
        user: UserRecord,
        orgId: Long,
    ): String? {
        if (user.isPlatformAdmin) return OrgService.ROLE_ADMIN
        val uid = user.id ?: return null
        return orgs.orgRole(uid, orgId)
    }

    fun isOrgAdmin(
        user: UserRecord,
        orgId: Long,
    ): Boolean = user.isPlatformAdmin || orgs.isOrgAdmin(user.id ?: return false, orgId)

    /**
     * 判定用户对某 repo（project）的角色。
     * 平台 admin / org_admin 恒 owner；普通用户按项目 membership。
     */
    fun roleForRepo(
        req: HttpServletRequest,
        repoId: Long,
    ): String? {
        val user = currentUser(req) ?: return null
        return effectiveRole(user, repoService.getRepo(repoId)?.projectId)
    }

    /** 平台 admin / org_admin 恒 owner；普通用户按项目 membership。无 projectId 视为无权限。 */
    fun effectiveRole(
        user: UserRecord,
        projectId: Long?,
    ): String? {
        if (user.isPlatformAdmin) return Role.OWNER
        val uid = user.id ?: return null
        val project = projectId?.let { projectService.get(it) } ?: return null
        val orgId = project.orgId ?: orgs.defaultOrgId()
        if (orgs.isOrgAdmin(uid, orgId)) return Role.OWNER
        return projectId.let { users.membershipRole(uid, it) }
    }

    /** 项目可读判定：成员可读 draft/release；同组织非成员仅 internal/public 的 release；匿名仅 public release。 */
    fun canReadProject(
        user: UserRecord?,
        project: ProjectEntity,
        stage: String,
    ): Boolean {
        if (user == null) {
            return project.visibility == ProjectService.VISIBILITY_PUBLIC && stage == Stage.RELEASE
        }
        if (user.isPlatformAdmin) return true
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

    /** 可管理项目：org_admin 或项目 admin+。 */
    fun canManageProject(
        user: UserRecord,
        project: ProjectEntity,
    ): Boolean = Role.rank(effectiveRole(user, project.id)) >= Role.rank(Role.ADMIN)

    /**
     * 读端点入口：项目不存在或不可见统一 404（不暴露资源是否存在）。
     * @return 可见项目（供调用方继续取 projectId / orgId）。
     */
    fun requireProjectVisible(
        req: HttpServletRequest,
        projectId: Long,
        stage: String = Stage.RELEASE,
    ): ProjectEntity {
        val project =
            projectService.get(projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        if (!canReadProject(currentUser(req), project, stage)) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        }
        return project
    }

    /** 读端点入口：repo 不存在或不可见统一 404。 */
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

    /** require：写端点入口校验，未达标抛 401/403（带操作名）；未启用鉴权放行 */
    fun requireRepoRole(
        req: HttpServletRequest,
        repoId: Long,
        min: String,
        op: String,
    ) {
        if (!authEnabled) return
        val user =
            currentUser(req)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val role = roleForRepo(req, repoId)
        if (role == null || Role.rank(role) < Role.rank(min)) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要 $min 及以上角色（当前 ${role ?: "无项目权限"}）")
        }
    }

    /** require：面向项目（注册仓库等尚无 repoId 的场景）；未启用鉴权放行 */
    fun requireProjectRole(
        req: HttpServletRequest,
        projectId: Long?,
        min: String,
        op: String,
    ) {
        if (!authEnabled) return
        val user =
            currentUser(req)
                ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
        val role = effectiveRole(user, projectId)
        if (role == null || Role.rank(role) < Role.rank(min)) {
            throw ApiException(ApiErrorCode.AUTH_FORBIDDEN, "$op 需要 $min 及以上角色（当前 ${role ?: "无项目权限"}）")
        }
    }

    /** require：管理端点（成员/角色管理），需要目标 repo 所在项目 admin+ 或 org_admin */
    fun requireAdmin(
        req: HttpServletRequest,
        repoId: Long,
        op: String,
    ) = requireRepoRole(req, repoId, Role.ADMIN, op)

    /** require：轻交互（评论等）需「有登录用户」即可，不必 editor+；未启用鉴权放行 */
    fun requireAuthenticated(
        req: HttpServletRequest,
        op: String,
    ): UserRecord {
        if (!authEnabled) {
            return currentUser(req) ?: UserRecord(id = -1, username = "anonymous", tokenHash = "", isPlatformAdmin = false)
        }
        return currentUser(req) ?: throw ApiException(ApiErrorCode.AUTH_UNAUTHORIZED, "$op 需要登录")
    }
}
