package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 项目级服务账号（V6；C5 提供管理 API）。
 *
 * - `project_id NOT NULL`，`org_id` 冗余；角色仅 viewer / editor。
 * - 只存 SHA-256 hash；不可登录 Web，只走 Bearer API 链。
 */
@Table("service_account")
data class ServiceAccountEntity(
    @Id val id: Long? = null,
    @Column("org_id") val orgId: Long,
    @Column("project_id") val projectId: Long,
    val name: String,
    @Column("token_prefix") val tokenPrefix: String,
    @Column("token_hash") val tokenHash: String,
    val role: String,
    val status: String = ServiceAccountStatus.ACTIVE,
    @Column("expires_at") val expiresAt: Instant,
    @Column("last_used_at") val lastUsedAt: Instant? = null,
    @Column("revoked_at") val revokedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant,
)

object ServiceAccountStatus {
    const val ACTIVE = "active"
    const val REVOKED = "revoked"
}
