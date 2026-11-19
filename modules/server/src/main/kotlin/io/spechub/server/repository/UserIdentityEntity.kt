package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * OIDC 身份绑定（V6；C6 写入）。
 * `provider = "oidc:<issuer>"`；`subject = sub`，二者大小写敏感。
 */
@Table("user_identity")
data class UserIdentityEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    val provider: String,
    val subject: String,
    val email: String? = null,
    @Column("created_at") val createdAt: Instant,
)
