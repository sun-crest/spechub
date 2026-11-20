package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 用户 API 令牌（V6）。只存 SHA-256 hash；`token_prefix` 仅用于列表展示。
 *
 * - `legacy`：由旧 `app_user` 令牌列迁移来的长期令牌（无过期，V7 后仍有效）。
 * - `api` / `invite` / `password_reset`：必须有过期时间；邀请 / 重置使用后写 `used_at`。
 */
@Table("user_token")
data class UserTokenEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    val name: String,
    @Column("token_prefix") val tokenPrefix: String = "",
    @Column("token_hash") val tokenHash: String,
    val purpose: String,
    @Column("expires_at") val expiresAt: Instant? = null,
    @Column("used_at") val usedAt: Instant? = null,
    @Column("last_used_at") val lastUsedAt: Instant? = null,
    @Column("revoked_at") val revokedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant,
)
