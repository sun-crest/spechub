package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `subscription`：消费团队对某接口（repo + method + path）的变更订阅。 */
@Table("subscription")
data class SubscriptionEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    @Column("org_id") val orgId: Long? = null,
    @Column("api_id") val apiId: Long? = null,
    val method: String,
    val path: String,
    @Column("team_id") val teamId: Long? = null,
    /** 团队名快照；读取时以 team_id 关联的当前名为准（B2） */
    val team: String,
    @Column("notify_url") val notifyUrl: String = "",
    @Column("created_at") val createdAt: Instant,
)
