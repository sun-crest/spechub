package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `team_member`：团队成员（lead / member）；V5 起使用代理主键 id。 */
@Table("team_member")
data class TeamMemberEntity(
    @Id val id: Long? = null,
    @Column("team_id") val teamId: Long,
    @Column("user_id") val userId: Long,
    val role: String,
    @Column("created_at") val createdAt: Instant,
)
