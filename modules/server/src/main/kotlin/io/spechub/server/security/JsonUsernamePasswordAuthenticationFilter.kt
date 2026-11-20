package io.spechub.server.security

import io.spechub.server.service.AuditService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.session.FindByIndexNameSessionRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.time.Instant

/**
 * `POST /api/auth/login` JSON 登录（阶段 C）：
 * - `{username|email, password}` → DaoAuthenticationProvider + Argon2id；
 * - `{username?, token}` → opaque user token（legacy / api；服务账号不可登录 Web）；
 * - 成功后由父类写入 Spring Session（会话固定防护由 SessionAuthenticationStrategy 完成）。
 * 不启用默认 form login。
 */
class JsonUsernamePasswordAuthenticationFilter(
    private val json: ObjectMapper,
    authenticationManager: AuthenticationManager,
    private val csrfTokenRepository: CsrfTokenRepository,
    private val rateLimiter: RedisLoginRateLimiter,
    private val audit: AuditService,
) : AbstractAuthenticationProcessingFilter(LoginRequestMatcher()) {
    companion object {
        const val LOGIN_PATH = "/api/auth/login"
        const val AUTH_METHOD_ATTRIBUTE = "spechub.auth.method"
        const val LOGIN_IDENTIFIER_ATTRIBUTE = "spechub.auth.identifier"
    }

    init {
        this.authenticationManager = authenticationManager
    }

    override fun attemptAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Authentication {
        val body: JsonNode =
            try {
                json.readTree(request.inputStream)
            } catch (e: IOException) {
                throw LoginBadRequestException("请求体 JSON 格式错误")
            } ?: throw LoginBadRequestException("请求体不能为空")

        val username =
            body
                .get("username")
                ?.asString()
                ?.trim()
                ?.ifBlank { null }
        val email =
            body
                .get("email")
                ?.asString()
                ?.trim()
                ?.ifBlank { null }
        val password = body.get("password")?.asString()?.ifBlank { null }
        val token =
            body
                .get("token")
                ?.asString()
                ?.trim()
                ?.ifBlank { null }

        if ((password != null) == (token != null)) {
            throw LoginBadRequestException("password 与 token 必须且只能提供一个")
        }

        if (password != null) {
            request.setAttribute(AUTH_METHOD_ATTRIBUTE, "password")
            val identifier = username ?: email ?: throw BadCredentialsException("缺少用户名或邮箱")
            request.setAttribute(LOGIN_IDENTIFIER_ATTRIBUTE, identifier)
            val ip = request.remoteAddr ?: "unknown"
            rateLimiter.check(identifier, ip)?.let { throw LoginRateLimitedException(it) }
            val authentication =
                try {
                    authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(identifier, password))
                } catch (e: AuthenticationException) {
                    rateLimiter.onFailure(identifier, ip)
                    throw e
                }
            rateLimiter.onSuccess(identifier, ip)
            return authentication
        }

        request.setAttribute(AUTH_METHOD_ATTRIBUTE, "token")
        if (username != null) request.setAttribute(LOGIN_IDENTIFIER_ATTRIBUTE, username)
        val authentication =
            try {
                authenticationManager.authenticate(OpaqueTokenAuthenticationToken.unauthenticated(checkNotNull(token)))
            } catch (e: AuthenticationServiceException) {
                throw BadCredentialsException("令牌无效")
            }
        val principal = authentication.principal
        if (principal is ServicePrincipal) {
            throw BadCredentialsException("服务账号不可登录 Web")
        }
        if (username != null && principal is AppUserPrincipal && !username.equals(principal.username, ignoreCase = true)) {
            throw BadCredentialsException("用户名与令牌不匹配")
        }
        return authentication
    }
}

/** 登录路径匹配：仅 `POST /api/auth/login`。 */
private class LoginRequestMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean = request.method.equals("POST", ignoreCase = true) && request.requestURI == JsonUsernamePasswordAuthenticationFilter.LOGIN_PATH
}

/** 请求体 / 参数非法（400，而非 401）。 */
class LoginBadRequestException(
    message: String,
) : AuthenticationException(message)

/** 登录成功：返回会话 / CSRF / mustChangePassword，并确保 Spring Session 的 principal 索引可用。 */
class LoginSuccessHandler(
    private val json: ObjectMapper,
    private val csrfTokenRepository: CsrfTokenRepository,
    private val users: UserService,
    private val audit: AuditService,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val principal = authentication.principal as? AppUserPrincipal ?: return
        users.update(principal.userId, lastLoginAt = Instant.now())
        val authMethod = request.getAttribute(JsonUsernamePasswordAuthenticationFilter.AUTH_METHOD_ATTRIBUTE) ?: "session"
        val csrfToken = csrfTokenRepository.generateToken(request)
        csrfTokenRepository.saveToken(csrfToken, request, response)
        val session = request.getSession(true)
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.username)
        session.setAttribute(SessionAttributes.PRINCIPAL_NAME, principal.username)
        session.setAttribute(SessionAttributes.IP, request.remoteAddr)
        session.setAttribute(SessionAttributes.USER_AGENT, request.getHeader("User-Agent"))
        session.setAttribute(SessionAttributes.LOGIN_AT, Instant.now().toString())
        session.setAttribute(SessionAttributes.AUTH_METHOD, authMethod)

        audit.recordAuth(
            orgId = audit.defaultOrgId(),
            actor = AuditService.AuditActor(principal.userId, principal.username, AuditService.ACTOR_USER),
            action = "auth.login.success",
            targetType = "user",
            targetId = principal.userId.toString(),
            detail = "登录成功（$authMethod）",
            outcome = AuditService.OUTCOME_SUCCESS,
            request = request,
        )

        val body =
            mapOf(
                "authenticated" to true,
                "authEnabled" to true,
                "authMethod" to authMethod,
                "username" to principal.username,
                "displayName" to principal.displayName,
                "csrfToken" to csrfToken.token,
                "mustChangePassword" to principal.mustChangePassword,
            )
        response.status = HttpServletResponse.SC_OK
        response.contentType = "application/json"
        response.characterEncoding = Charsets.UTF_8.name()
        json.writeValue(response.writer, body)
    }
}

/** 登录失败：统一 401 文案（不泄露账号是否存在）；请求体问题返回 400；认证事件尽力写审计。 */
class LoginFailureHandler(
    private val entryPoint: ApiAuthenticationEntryPoint,
    private val audit: AuditService,
) : AuthenticationFailureHandler {
    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        SecurityContextHolder.clearContext()
        recordAudit(request, exception)
        when (exception) {
            is LoginRateLimitedException -> {
                response.setHeader("Retry-After", exception.retryAfterSeconds.toString())
                entryPoint.writeError(request, response, ApiErrorCode.TOO_MANY_REQUESTS, "登录尝试过于频繁，请稍后再试")
            }

            is LoginRateLimitUnavailableException -> {
                entryPoint.writeError(request, response, ApiErrorCode.SERVICE_UNAVAILABLE, "登录服务暂不可用")
            }

            is LoginBadRequestException -> {
                entryPoint.writeError(request, response, ApiErrorCode.BAD_REQUEST, exception.message ?: "请求不合法")
            }

            is BadCredentialsException, is DisabledException, is LockedException, is AuthenticationServiceException -> {
                entryPoint.writeError(request, response, ApiErrorCode.AUTH_UNAUTHORIZED, "用户名或密码错误或账号不可用")
            }

            else -> {
                entryPoint.writeError(request, response, ApiErrorCode.AUTH_UNAUTHORIZED, "登录失败")
            }
        }
    }

    private fun recordAudit(
        request: HttpServletRequest,
        exception: AuthenticationException,
    ) {
        if (exception is LoginBadRequestException) return
        val identifier = request.getAttribute(JsonUsernamePasswordAuthenticationFilter.LOGIN_IDENTIFIER_ATTRIBUTE) as? String
        val actor = audit.actorForIdentifier(identifier)
        val action =
            when (exception) {
                is LoginRateLimitedException -> "auth.login.locked"
                else -> "auth.login.failure"
            }
        val detail =
            when (exception) {
                is LoginRateLimitedException -> "登录尝试过于频繁"
                is LoginRateLimitUnavailableException -> "登录限流不可用"
                else -> "用户名或密码错误或账号不可用"
            }
        audit.recordAuth(
            orgId = audit.defaultOrgId(),
            actor = actor,
            action = action,
            targetType = "user",
            targetId = actor.userId?.toString() ?: identifier,
            detail = detail,
            outcome = AuditService.OUTCOME_FAILURE,
            request = request,
        )
    }
}
