package io.spechub.server.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.session.FindByIndexNameSessionRepository
import java.time.Instant

/** OIDC 成功：只允许站内相对路径（固定 `/`），会话由 oauth2Login + Spring Session 写入。 */
class OidcLoginSuccessHandler : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val session = request.getSession(true)
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, authentication.name)
        session.setAttribute(SessionAttributes.PRINCIPAL_NAME, authentication.name)
        session.setAttribute(SessionAttributes.IP, request.remoteAddr)
        session.setAttribute(SessionAttributes.USER_AGENT, request.getHeader("User-Agent"))
        session.setAttribute(SessionAttributes.LOGIN_AT, Instant.now().toString())
        session.setAttribute(SessionAttributes.AUTH_METHOD, "oidc")
        response.sendRedirect("/")
    }
}

/** OIDC 失败：不把 IdP 错误详情放 URL，只给固定标记供登录页提示。 */
class OidcLoginFailureHandler : AuthenticationFailureHandler {
    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        response.sendRedirect("/?loginError=oidc")
    }
}
