package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.service.RepoService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * I4 —— RBAC 认证与角色判定（§6.3 Project.members、§6.4 决策 3 的权限底座）。
 *
 * 模型：
 * - 用户 = `app_user`（username + token，库中只存 SHA-256 hash）；token 由平台发放、前端持有。
 * - `spechub.admin-token` 启动时 bootstrap 为 `admin` 用户（is_platform_admin）：对任意项目视为 owner，
 *   与 H6 的 Bearer admin-token 完全向后兼容。
 * - 角色 per project（membership）：viewer < editor < admin < owner。
 * - 未配置 admin-token → 不启用鉴权：全放行（本地开发 / compose 最小部署，与 P0 一致）。
 */
@Service
class AuthService(
    private val repoService: RepoService,
    private val users: UserService,
    private val props: SpecHubProperties,
) {
    /** 请求属性名：鉴权后放当前用户 */
    companion object {
        const val REQ_USER = "spechub.auth.user"
        const val REQ_USERNAME = "spechub.auth.username"

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

    /** request 上的操作者用户名（filter 已填充）；无则回退 anonymous（单一实现，勿在 Controller 重复取属性） */
    fun currentActor(req: HttpServletRequest): String = req.getAttribute(REQ_USERNAME) as? String ?: "anonymous"

    /**
     * 判定用户对某 repo（project）是否至少 [min] 角色。
     * @return 达标 → 角色名；未达标 → null。
     */
    fun roleForRepo(
        req: HttpServletRequest,
        repoId: Long,
    ): String? {
        val user = currentUser(req) ?: return null
        return effectiveRole(user, repoService.getRepo(repoId)?.projectId)
    }

    /** 平台 admin 恒 owner；普通用户按 membership。无 projectId 视为无权限。 */
    fun effectiveRole(
        user: UserRecord,
        projectId: Long?,
    ): String? {
        if (user.isPlatformAdmin) return Role.OWNER
        val uid = user.id ?: return null
        return projectId?.let { users.membershipRole(uid, it) }
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

    /** require：面向项目（注册仓库等尚无 repoId 的场景）；平台 admin 恒通过；未启用鉴权放行 */
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

    /** require：管理端点（成员/角色管理），需要目标 repo 所在项目 admin+ 或平台 admin */
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
