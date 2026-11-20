package io.spechub.server.security

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.service.OidcLoginException
import io.spechub.server.service.OidcLoginService
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * 把 Spring Security 的 OidcUser（ID Token / UserInfo）转换为 SpecHub 本地 principal。
 *
 * 业务解析（身份绑定 / 邮箱绑定 / auto-provision / 状态校验）在 [OidcLoginService]；
 * 这里只做声明提取，异常统一转为 [OAuth2AuthenticationException] 交给失败 handler 重定向。
 */
class AppOidcUserService(
    private val loginService: OidcLoginService,
    private val props: SpecHubProperties,
) : OidcUserService() {
    override fun loadUser(userRequest: OidcUserRequest): OidcUser {
        val oidcUser = super.loadUser(userRequest)
        val idToken = oidcUser.idToken
        val issuer =
            idToken.issuer?.toString()
                ?: userRequest.clientRegistration.providerDetails.issuerUri
                ?: userRequest.clientRegistration.registrationId
        val subject =
            idToken.subject
                ?: oidcUser.getAttribute<Any>("sub")?.toString()
                ?: throw OAuth2AuthenticationException(OAuth2Error("oidc_login_failed", "OIDC subject 缺失", null))
        val emailClaim = props.oidc.emailClaim
        val email = oidcUser.getAttribute<Any>(emailClaim) as? String ?: oidcUser.getAttribute<Any>("email") as? String
        val emailVerified = parseVerified(oidcUser.getAttribute<Any>("email_verified"))
        val name = oidcUser.getAttribute<Any>("name") as? String ?: oidcUser.getAttribute<Any>("preferred_username") as? String

        val resolved =
            try {
                loginService.resolve(
                    OidcLoginService.OidcClaims(
                        issuer = issuer,
                        subject = subject,
                        email = email,
                        emailVerified = emailVerified,
                        name = name,
                        claims = idToken.claims,
                        idToken = idToken,
                    ),
                )
            } catch (e: OidcLoginException) {
                throw OAuth2AuthenticationException(OAuth2Error("oidc_login_failed", e.message, null), e)
            }
        return resolved
    }

    private fun parseVerified(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
}
