package io.spechub.server.security

import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserStatus
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable
import java.security.Principal

/**
 * Spring Session 中持久化的最小用户 principal。
 *
 * - 只带 id / username / displayName / status / mustChangePassword；**不写 UserRecord / 实体**。
 * - `passwordHash` 标记 `@Transient`：登录时供 DaoAuthenticationProvider 校验，Java 序列化进 Redis
 *   会话时被丢弃，避免密码哈希随会话落盘。
 * - `getName()` 返回本地 username，保证 Spring Session 的 PRINCIPAL_NAME 索引可用。
 */
data class AppUserPrincipal(
    val userId: Long,
    @get:JvmName("principalUsername") val username: String,
    val displayName: String = "",
    val status: String = UserStatus.ACTIVE,
    val mustChangePassword: Boolean = false,
    @Transient val passwordHash: String? = null,
) : UserDetails,
    Principal,
    Serializable {
    override fun getAuthorities(): Collection<GrantedAuthority> = emptyList()

    override fun getPassword(): String = passwordHash ?: ""

    override fun getUsername(): String = username

    override fun getName(): String = username

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = status in UserStatus.LOGINNABLE

    /** 不把 passwordHash 写进日志 / 异常栈。 */
    override fun toString(): String =
        "AppUserPrincipal(userId=$userId, username=$username, displayName=$displayName, " +
            "status=$status, mustChangePassword=$mustChangePassword)"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 项目级服务账号 principal：不可登录 Web，只在其 projectId 内有效。 */
data class ServicePrincipal(
    val accountId: Long,
    @get:JvmName("principalName") val name: String,
    val orgId: Long,
    val projectId: Long,
    val role: String,
) : Principal,
    Serializable {
    override fun getName(): String = "service:$name"

    companion object {
        private const val serialVersionUID = 1L
    }
}

fun UserEntity.toPrincipal(): AppUserPrincipal =
    AppUserPrincipal(
        userId = checkNotNull(id) { "已持久化用户必有 id" },
        username = username,
        displayName = displayName,
        status = status,
        mustChangePassword = mustChangePassword,
        passwordHash = passwordHash,
    )
