package io.spechub.server.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

/**
 * opaque Bearer / 登录令牌的认证请求令牌。
 * 密码路径仍用 UsernamePasswordAuthenticationToken，令牌路径用本类型以区分 provider。
 */
class OpaqueTokenAuthenticationToken private constructor(
    private val principalValue: Any,
    private val tokenValue: String?,
    authorities: Collection<GrantedAuthority>,
    authenticated: Boolean,
) : AbstractAuthenticationToken(authorities) {
    init {
        isAuthenticated = authenticated
    }

    override fun getCredentials(): Any? = tokenValue

    override fun getPrincipal(): Any = principalValue

    companion object {
        /** 认证前 principal 只放占位符，避免 raw token 进日志；明文放 credentials（toString 恒为 [PROTECTED]）。 */
        fun unauthenticated(raw: String): OpaqueTokenAuthenticationToken = OpaqueTokenAuthenticationToken("[token]", raw, emptyList(), false)

        fun authenticated(
            principal: Any,
            authorities: Collection<GrantedAuthority> = emptyList(),
        ): OpaqueTokenAuthenticationToken = OpaqueTokenAuthenticationToken(principal, null, authorities, true)
    }
}
