package io.spechub.server.security

import io.spechub.server.service.AuditService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler

/** C7：登出审计（尽力而为）+ 幂等 204。 */
class AuditLogoutSuccessHandler(
    private val audit: AuditService,
) : LogoutSuccessHandler {
    override fun onLogoutSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?,
    ) {
        val actor = audit.actorFor(authentication)
        audit.recordAuth(
            orgId = audit.defaultOrgId(),
            actor = actor,
            action = "auth.logout",
            targetType = "user",
            targetId = actor.userId?.toString(),
            detail = "退出登录",
            outcome = AuditService.OUTCOME_SUCCESS,
            request = request,
        )
        response.status = HttpServletResponse.SC_NO_CONTENT
    }
}
