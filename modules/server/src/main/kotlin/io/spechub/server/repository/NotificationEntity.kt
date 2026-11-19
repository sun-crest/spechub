package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `notification`：摄取检测订阅接口结构变化时落条（消费团队可查，历史留痕）。 */
@Table("notification")
data class NotificationEntity(
    @Id val id: Long? = null,
    @Column("team_id") val teamId: Long? = null,
    /** 投递时团队名快照；团队删除后仍可展示 */
    val team: String,
    @Column("repo_id") val repoId: Long,
    @Column("org_id") val orgId: Long? = null,
    val method: String,
    val path: String,
    val summary: String,
    @Column("created_at") val createdAt: Instant,
)
