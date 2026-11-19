package io.spechub.server.security

import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException
import tools.jackson.databind.ObjectMapper

/**
 * 403 契约：始终返回 `ApiErrorBody` / `ApiErrorCode`。
 *
 * CSRF 失败（CsrfException 在 CsrfFilter 内抛出，早于 ExceptionTranslationFilter）：
 * - 未认证请求 → 401（避免把「没登录」误报成权限不足）；
 * - 已认证 Web 会话请求 → 403 + `AUTH_CSRF`。
 */
class ApiAccessDeniedHandler(
    private val json: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        if (accessDeniedException is CsrfException) {
            val authentication = SecurityContextHolder.getContext().authentication
            val anonymous =
                authentication == null || !authentication.isAuthenticated || authentication is AnonymousAuthenticationToken
            write(
                request,
                response,
                if (anonymous) ApiErrorCode.AUTH_UNAUTHORIZED else ApiErrorCode.AUTH_CSRF,
                if (anonymous) "未认证或凭据无效" else "CSRF token 缺失或非法",
            )
            return
        }
        write(request, response, ApiErrorCode.AUTH_FORBIDDEN, "权限不足")
    }

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        code: ApiErrorCode,
        message: String,
    ) {
        response.status = code.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.writer, ApiErrorBody(code.status, code.name, message, request.requestURI))
    }
}
