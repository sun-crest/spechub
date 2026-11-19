package io.spechub.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 会话绝对超时（默认 7 天）：读取 Spring Session 的 creationTime，超时后 invalidate 并返回 401。
 * 空闲超时由 `spring.session.timeout`（默认 24h）负责。
 */
class AbsoluteSessionTimeoutFilter(
    private val timeoutSeconds: Long,
    private val entryPoint: ApiAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    private val exemptPaths =
        listOf(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/status",
            "/actuator/",
            "/mock/",
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val session = request.getSession(false)
        if (session != null) {
            val created = session.creationTime
            val expired = created > 0 && System.currentTimeMillis() - created > timeoutSeconds * 1000
            if (expired) {
                runCatching { session.invalidate() }
                SecurityContextHolder.clearContext()
                if (exemptPaths.none { request.requestURI.startsWith(it) }) {
                    entryPoint.commence(request, response, InsufficientAuthenticationException("会话已超过绝对有效期"))
                    return
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
