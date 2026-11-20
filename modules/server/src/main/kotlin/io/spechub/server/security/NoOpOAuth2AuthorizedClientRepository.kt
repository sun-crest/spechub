package io.spechub.server.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository

/**
 * OIDC 只做登录：**不保存 IdP access / refresh token**（不写 Spring Session）。
 * oauth2Login 仍能完成 code 换 token 与 ID Token 校验，但授权客户端不落任何存储。
 */
class NoOpOAuth2AuthorizedClientRepository : OAuth2AuthorizedClientRepository {
    override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        request: HttpServletRequest,
    ): T? = null

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        // intentionally no-op
    }

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        // intentionally no-op
    }
}
