package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `team`：组织级团队（ADR §3.3 / §4.1），订阅与通知的归属方。 */
@Table("team")
data class TeamEntity(
    @Id val id: Long? = null,
    @Column("org_id") val orgId: Long,
    val name: String,
    val description: String = "",
    @Column("created_at") val createdAt: Instant,
)
