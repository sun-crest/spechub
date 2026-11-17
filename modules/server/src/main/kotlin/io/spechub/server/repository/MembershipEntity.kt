package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/** 管理库 `membership`（user → project 的角色）。project 数据迁入前 projectId 为逻辑引用。 */
@Table("membership")
data class MembershipEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    @Column("project_id") val projectId: Long,
    val role: String,
)
