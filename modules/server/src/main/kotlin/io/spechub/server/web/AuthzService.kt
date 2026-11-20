package io.spechub.server.web

import io.spechub.server.security.AppUserPrincipal
import io.spechub.server.security.OidcAppUserPrincipal
import io.spechub.server.security.ServicePrincipal
import io.spechub.server.service.ProjectService
import io.spechub.server.service.UserService
import io.spechub.spec.index.Role
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service

/**
 * 面向 `Authentication` 的授权门面（C2 先供服务层 / 测试使用；后续可接 `AuthorizationManager` / `@PreAuthorize`）。
 * 内部委托 [AuthService]，域规则仍集中在一处。
 */
@Service
class AuthzService(
    private val auth: AuthService,
    private val users: UserService,
    private val projects: ProjectService,
) {
    fun isOrgAdmin(
        authentication: Authentication?,
        orgId: Long,
    ): Boolean {
        val userId =
            when (val principal = authentication?.principal) {
                is AppUserPrincipal -> principal.userId
                is OidcAppUserPrincipal -> principal.appUserId
                else -> return false
            }
        return auth.isOrgAdminById(userId, orgId)
    }

    fun hasProjectRole(
        authentication: Authentication?,
        projectId: Long,
        min: String,
    ): Boolean =
        when (val principal = authentication?.principal) {
            is ServicePrincipal -> {
                principal.projectId == projectId && Role.rank(principal.role) >= Role.rank(min)
            }

            is AppUserPrincipal -> {
                val user = users.findById(principal.userId)
                val role = user?.let { auth.effectiveRole(it, projectId) }
                role != null && Role.rank(role) >= Role.rank(min)
            }

            is OidcAppUserPrincipal -> {
                val user = users.findById(principal.appUserId)
                val role = user?.let { auth.effectiveRole(it, projectId) }
                role != null && Role.rank(role) >= Role.rank(min)
            }

            else -> {
                false
            }
        }

    fun canReadProject(
        authentication: Authentication?,
        projectId: Long,
        stage: String,
    ): Boolean {
        val project = projects.get(projectId) ?: return false
        return when (val principal = authentication?.principal) {
            is ServicePrincipal -> {
                principal.projectId == projectId
            }

            is AppUserPrincipal -> {
                auth.canReadProject(users.findById(principal.userId), project, stage)
            }

            is OidcAppUserPrincipal -> {
                auth.canReadProject(users.findById(principal.appUserId), project, stage)
            }

            else -> {
                auth.canReadProject(null, project, stage)
            }
        }
    }
}
