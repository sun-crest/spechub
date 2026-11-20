package io.spechub.server.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import java.io.Serializable
import java.security.Principal

/**
 * OIDC 登录 principal（C6）：只带本地用户 id / username / displayName 与 ID Token 声明。
 * - 不存 IdP access / refresh token；
 * - `getName()` 返回本地 username，保证 Spring Session 的 PRINCIPAL_NAME 索引可用；
 * - 可 Java 序列化进 Redis 会话；toString 不输出 claims。
 */
data class OidcAppUserPrincipal(
    val appUserId: Long,
    @get:JvmName("principalUsername") val username: String,
    val displayName: String = "",
    @get:JvmName("principalClaims") val claims: Map<String, Any> = emptyMap(),
    @get:JvmName("principalIdToken") val idToken: OidcIdToken,
) : OidcUser,
    Principal,
    Serializable {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getAttributes(): Map<String, Any> = claims

    override fun getClaims(): Map<String, Any> = claims

    override fun getIdToken(): OidcIdToken = idToken

    /** 不额外保存 UserInfo：ID Token claims 已足够，避免会话膨胀。 */
    override fun getUserInfo(): OidcUserInfo? = null

    override fun getName(): String = username

    override fun toString(): String = "OidcAppUserPrincipal(appUserId=$appUserId, username=$username)"

    companion object {
        private const val serialVersionUID = 1L
    }
}
