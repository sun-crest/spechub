package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `app_user`（Phase 2：从投影库迁入，token 只存 SHA-256 hash）。 */
@Table("app_user")
data class UserEntity(
    @Id val id: Long? = null,
    val username: String,
    @Column("display_name") val displayName: String,
    @Column("token_hash") val tokenHash: String,
    @Column("is_platform_admin") val isPlatformAdmin: Boolean,
    @Column("created_at") val createdAt: Instant,
)
