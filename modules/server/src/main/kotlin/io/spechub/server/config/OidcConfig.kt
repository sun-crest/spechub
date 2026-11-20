package io.spechub.server.config

import io.spechub.server.security.AppOidcUserService
import io.spechub.server.service.OidcLoginService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames

/**
 * C6：OIDC 单 IdP 装配（只有 `spechub.oidc.enabled=true` 时才注册 bean）。
 *
 * - 从 [OidcProperties] 构造 `ClientRegistration`，不依赖 `spring.security.oauth2.client.*`；
 * - 未提供显式端点时启动发现 `issuer-uri`；显式端点齐全时跳过外联；
 * - Authorization Code + PKCE；`OidcUserService` 把 IdP 用户映射为本地 principal。
 */
@Configuration(proxyBeanMethods = false)
class OidcConfig {
    @Bean
    @ConditionalOnProperty(prefix = "spechub.oidc", name = ["enabled"], havingValue = "true")
    fun clientRegistrationRepository(props: SpecHubProperties): ClientRegistrationRepository = InMemoryClientRegistrationRepository(buildRegistration(props))

    @Bean
    @ConditionalOnProperty(prefix = "spechub.oidc", name = ["enabled"], havingValue = "true")
    fun oidcUserService(
        loginService: OidcLoginService,
        props: SpecHubProperties,
    ): OidcUserService = AppOidcUserService(loginService, props)

    private fun buildRegistration(props: SpecHubProperties): ClientRegistration {
        val oidc = props.oidc
        val issuer = checkNotNull(oidc.issuerUri) { "OIDC issuer-uri 未配置" }
        val clientId = checkNotNull(oidc.clientId) { "OIDC client-id 未配置" }
        val clientSecret = checkNotNull(oidc.clientSecret) { "OIDC client-secret 未配置" }
        val redirectUri = oidc.resolvedRedirectUri(props.externalUrl)
        val clientSettings = ClientSettings.builder().requireProofKey(true).build()

        return if (oidc.hasExplicitEndpoints()) {
            ClientRegistration
                .withRegistrationId(oidc.registrationId)
                .issuerUri(issuer)
                .authorizationUri(oidc.authorizationUri)
                .tokenUri(checkNotNull(oidc.tokenUri) { "OIDC token-uri 未配置" })
                .jwkSetUri(oidc.jwkSetUri)
                .userInfoUri(oidc.userInfoUri)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(oidc.scopes)
                .clientName("SpecHub")
                .clientSettings(clientSettings)
                .build()
        } else {
            ClientRegistrations
                .fromIssuerLocation(issuer)
                .registrationId(oidc.registrationId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(oidc.scopes)
                .clientName("SpecHub")
                .clientSettings(clientSettings)
                .build()
        }
    }
}
