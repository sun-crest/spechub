package io.spechub.server.security

import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import tools.jackson.databind.ObjectMapper

/** 401 契约：始终返回 `ApiErrorBody` / `ApiErrorCode`。 */
class ApiAuthenticationEntryPoint(
    private val json: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        writeError(request, response, ApiErrorCode.AUTH_UNAUTHORIZED, "未认证或凭据无效")
    }

    fun writeError(
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
