package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 管理动作审计（V6）。`org_id` / `actor_user_id` 不建外键：审计需在用户 / 组织删除后保留；
 * `actor_name` 存快照。`detail` 只存可审计摘要（禁止密码 / 令牌 / Cookie / spec 正文）。
 */
@Table("audit_event")
data class AuditEventEntity(
    @Id val id: Long? = null,
    @Column("org_id") val orgId: Long,
    @Column("actor_user_id") val actorUserId: Long? = null,
    @Column("actor_name") val actorName: String,
    @Column("actor_type") val actorType: String,
    val action: String,
    @Column("target_type") val targetType: String? = null,
    @Column("target_id") val targetId: String? = null,
    val detail: String? = null,
    val outcome: String,
    val ip: String? = null,
    @Column("user_agent") val userAgent: String? = null,
    @Column("created_at") val createdAt: Instant,
)
