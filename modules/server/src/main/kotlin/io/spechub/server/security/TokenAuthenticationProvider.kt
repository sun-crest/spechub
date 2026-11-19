package io.spechub.server.security

import io.spechub.server.repository.ServiceAccountRepository
import io.spechub.server.repository.ServiceAccountStatus
import io.spechub.server.service.UserTokenService
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import java.time.Duration
import java.time.Instant

/**
 * opaque Bearer 令牌认证：先查 user_token（legacy / api），未命中再查 service_account。
 * 前缀只用于展示 / secret 扫描识别，不作为唯一分流依据。
 */
class TokenAuthenticationProvider(
    private val userTokens: UserTokenService,
    private val serviceAccounts: ServiceAccountRepository,
) : AuthenticationProvider {
    companion object {
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(5)
    }

    override fun authenticate(authentication: Authentication): Authentication {
        val raw = authentication.credentials as? String ?: throw BadCredentialsException("缺少令牌")

        userTokens.resolveLogin(raw)?.let { resolved ->
            return OpaqueTokenAuthenticationToken.authenticated(resolved.user.toPrincipal())
        }

        val account = serviceAccounts.findByTokenHash(UserTokenService.sha256(raw))
        if (account != null) {
            val now = Instant.now()
            if (account.status != ServiceAccountStatus.ACTIVE || account.revokedAt != null || !account.expiresAt.isAfter(now)) {
                throw BadCredentialsException("服务账号已失效")
            }
            if (account.lastUsedAt == null || account.lastUsedAt.isBefore(now.minus(TOUCH_INTERVAL))) {
                serviceAccounts.save(account.copy(lastUsedAt = now))
            }
            val principal =
                ServicePrincipal(
                    accountId = checkNotNull(account.id) { "已持久化服务账号必有 id" },
                    name = account.name,
                    orgId = account.orgId,
                    projectId = account.projectId,
                    role = account.role,
                )
            return OpaqueTokenAuthenticationToken.authenticated(principal)
        }

        throw BadCredentialsException("无效令牌")
    }

    override fun supports(authentication: Class<*>): Boolean = OpaqueTokenAuthenticationToken::class.java.isAssignableFrom(authentication)
}
