package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 管理库 `app_user`（阶段 C）。
 *
 * - 身份全局唯一；密码只存 Argon2id hash，不再有长期 `token_hash`（V7 已删列）。
 * - `status ∈ active | disabled | invited`；`invited` 表示尚未设置密码，但不阻断已发放的令牌。
 * - `mfa_enabled` 仅预留列，C 阶段不实现 MFA 校验。
 */
@Table("app_user")
data class UserEntity(
    @Id val id: Long? = null,
    val username: String,
    @Column("display_name") val displayName: String,
    val email: String? = null,
    @Column("password_hash") val passwordHash: String? = null,
    @Column("password_updated_at") val passwordUpdatedAt: Instant? = null,
    val status: String = UserStatus.ACTIVE,
    @Column("must_change_password") val mustChangePassword: Boolean = false,
    @Column("mfa_enabled") val mfaEnabled: Boolean = false,
    @Column("last_login_at") val lastLoginAt: Instant? = null,
    @Column("created_at") val createdAt: Instant,
)

/** `app_user.status` 枚举值（DB CHECK 同源）。 */
object UserStatus {
    const val ACTIVE = "active"
    const val DISABLED = "disabled"
    const val INVITED = "invited"

    /** 可登录 / 可用令牌的状态；`disabled` 一律拒绝。 */
    val LOGINNABLE = setOf(ACTIVE, INVITED)
}
