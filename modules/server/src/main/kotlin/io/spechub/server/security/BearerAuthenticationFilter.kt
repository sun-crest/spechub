package io.spechub.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Bearer API 链认证 filter：解析 `Authorization: Bearer` → [AuthenticationManager]。
 * 无效 / 过期令牌直接走 entry point 401；不创建会话、不写 SecurityContextRepository。
 */
class BearerAuthenticationFilter(
    private val authenticationManager: AuthenticationManager,
    private val entryPoint: ApiAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val raw =
            request
                .getHeader("Authorization")
                ?.takeIf { it.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true) }
                ?.substring(7)
                ?.trim()
        if (raw.isNullOrEmpty()) {
            entryPoint.writeError(request, response, io.spechub.server.web.common.ApiErrorCode.AUTH_UNAUTHORIZED, "缺少 Bearer 令牌")
            return
        }

        val authentication =
            try {
                authenticationManager.authenticate(OpaqueTokenAuthenticationToken.unauthenticated(raw))
            } catch (e: AuthenticationException) {
                SecurityContextHolder.clearContext()
                entryPoint.commence(request, response, e)
                return
            }

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        try {
            filterChain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
