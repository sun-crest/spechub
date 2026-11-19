package io.spechub.server.security

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.repository.ServiceAccountRepository
import io.spechub.server.service.UserTokenService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.session.SessionRegistry
import org.springframework.security.core.userdetails.UserDetailsPasswordService
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.session.security.SpringSessionBackedSessionRegistry
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import tools.jackson.databind.ObjectMapper

/**
 * 阶段 C 认证底座：两条链。
 *
 * 1. **Bearer API 链**（存在 `Authorization: Bearer`）：STATELESS、无 CSRF、无会话；
 *    opaque user token / 服务账号，默认拒绝。
 * 2. **Web Session 链**：其余请求；Spring Session Data Redis、CSRF、JSON 密码登录；默认拒绝。
 *
 * 所有环境都必须真实登录：不注册 dev 放行链，也没有 `authEnabled=false` 分支。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val props: SpecHubProperties,
    private val json: ObjectMapper,
) {
    companion object {
        private const val SESSION_COOKIE = "spechub_session"
        private val DOCS_PATHS =
            listOf(
                "/doc.html",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/webjars/**",
                "/swagger-ui/**",
            )
    }

    // ── 认证 / 编码 / 会话 beans ──────────────────────────────────────────

    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    @Bean
    fun tokenAuthenticationProvider(
        userTokens: UserTokenService,
        serviceAccounts: ServiceAccountRepository,
    ): TokenAuthenticationProvider = TokenAuthenticationProvider(userTokens, serviceAccounts)

    @Bean
    fun authenticationManager(
        userDetailsService: UserDetailsService,
        passwordEncoder: PasswordEncoder,
        userDetailsPasswordService: UserDetailsPasswordService,
        tokenProvider: TokenAuthenticationProvider,
    ): AuthenticationManager {
        val dao = DaoAuthenticationProvider(userDetailsService)
        dao.setPasswordEncoder(passwordEncoder)
        dao.setUserDetailsPasswordService(userDetailsPasswordService)
        return ProviderManager(dao, tokenProvider)
    }

    @Bean
    fun apiAuthenticationEntryPoint(): ApiAuthenticationEntryPoint = ApiAuthenticationEntryPoint(json)

    @Bean
    fun apiAccessDeniedHandler(): ApiAccessDeniedHandler = ApiAccessDeniedHandler(json)

    @Bean
    fun csrfTokenRepository(): CsrfTokenRepository =
        HttpSessionCsrfTokenRepository().apply {
            setHeaderName("X-CSRF-Token")
            setParameterName("_csrf")
        }

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun sessionAuthenticationStrategy(sessionRegistry: SessionRegistry): SessionAuthenticationStrategy =
        CompositeSessionAuthenticationStrategy(
            listOf(
                ChangeSessionIdAuthenticationStrategy(),
                RegisterSessionAuthenticationStrategy(sessionRegistry),
            ),
        )

    @Bean
    fun sessionRegistry(repository: FindByIndexNameSessionRepository<out Session>): SessionRegistry = SpringSessionBackedSessionRegistry(repository)

    @Bean
    fun cookieSerializer(): CookieSerializer =
        DefaultCookieSerializer().apply {
            setCookieName(SESSION_COOKIE)
            setUseHttpOnlyCookie(true)
            setSameSite("Lax")
            setCookiePath("/")
            setUseSecureCookie(props.sessionCookieSecure)
        }

    // ── 链 1：Bearer API ──────────────────────────────────────────────────

    @Bean
    @Order(1)
    fun bearerSecurityFilterChain(
        http: HttpSecurity,
        authenticationManager: AuthenticationManager,
        entryPoint: ApiAuthenticationEntryPoint,
        deniedHandler: ApiAccessDeniedHandler,
    ): SecurityFilterChain {
        http.securityMatcher(BearerRequestMatcher())
        http.csrf { it.disable() }
        http.formLogin { it.disable() }
        http.httpBasic { it.disable() }
        http.logout { it.disable() }
        http.requestCache { it.disable() }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        http.exceptionHandling {
            it.authenticationEntryPoint(entryPoint)
            it.accessDeniedHandler(deniedHandler)
        }
        http.addFilterBefore(BearerAuthenticationFilter(authenticationManager, entryPoint), AuthorizationFilter::class.java)
        http.authorizeHttpRequests { authorize ->
            authorize.requestMatchers(*bearerPermitAll().toTypedArray()).permitAll()
            authorize.anyRequest().authenticated()
        }
        return http.build()
    }

    // ── 链 2：Web Session ─────────────────────────────────────────────────

    @Bean
    @Order(2)
    fun webSecurityFilterChain(
        http: HttpSecurity,
        authenticationManager: AuthenticationManager,
        sessionStrategy: SessionAuthenticationStrategy,
        securityContextRepository: SecurityContextRepository,
        csrfTokenRepository: CsrfTokenRepository,
        entryPoint: ApiAuthenticationEntryPoint,
        deniedHandler: ApiAccessDeniedHandler,
    ): SecurityFilterChain {
        http.csrf { csrf ->
            csrf.csrfTokenRepository(csrfTokenRepository)
            csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            csrf.ignoringRequestMatchers(*csrfIgnorePaths().toTypedArray())
        }
        http.formLogin { it.disable() }
        http.httpBasic { it.disable() }
        http.requestCache { it.disable() }
        http.sessionManagement {
            it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            it.sessionFixation { fixation -> fixation.changeSessionId() }
        }
        http.securityContext { it.securityContextRepository(securityContextRepository) }
        http.exceptionHandling {
            it.authenticationEntryPoint(entryPoint)
            it.accessDeniedHandler(deniedHandler)
        }

        val loginFilter =
            JsonUsernamePasswordAuthenticationFilter(json, authenticationManager, csrfTokenRepository).apply {
                setSessionAuthenticationStrategy(sessionStrategy)
                setSecurityContextRepository(securityContextRepository)
                setAuthenticationSuccessHandler(LoginSuccessHandler(json, csrfTokenRepository))
                setAuthenticationFailureHandler(LoginFailureHandler(entryPoint))
            }
        http.addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter::class.java)
        http.addFilterBefore(
            AbsoluteSessionTimeoutFilter(props.sessionAbsoluteTimeoutSeconds, entryPoint),
            AuthorizationFilter::class.java,
        )

        http.logout { logout ->
            logout.logoutUrl("/api/auth/logout")
            logout.logoutSuccessHandler { _, response, _ -> response.status = HttpServletResponse.SC_NO_CONTENT }
            logout.deleteCookies(SESSION_COOKIE)
            logout.clearAuthentication(true)
        }

        http.authorizeHttpRequests { authorize ->
            authorize.requestMatchers(*webPermitAll().toTypedArray()).permitAll()
            authorize.anyRequest().authenticated()
        }
        return http.build()
    }

    private fun bearerPermitAll(): List<String> =
        buildList {
            add("/api/auth/status")
            add("/actuator/**")
            add("/mock/**")
            add("/error")
            if (props.openapiPublic) addAll(DOCS_PATHS)
        }

    private fun webPermitAll(): List<String> =
        buildList {
            add("/api/auth/status")
            add("/api/auth/login")
            add("/api/auth/logout")
            add("/api/auth/password/setup")
            add("/oauth2/**")
            add("/login/oauth2/**")
            add("/actuator/**")
            add("/mock/**")
            add("/error")
            if (props.openapiPublic) addAll(DOCS_PATHS)
        }

    private fun csrfIgnorePaths(): List<String> =
        listOf(
            "/api/auth/login",
            "/api/auth/logout",
            "/api/auth/password/setup",
            "/oauth2/**",
            "/login/oauth2/**",
            "/actuator/**",
            "/mock/**",
        )
}
