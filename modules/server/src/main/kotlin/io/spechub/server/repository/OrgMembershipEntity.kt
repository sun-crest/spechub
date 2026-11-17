package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `org_membership`：用户 × 组织（阶段 A 只有默认组织）。 */
@Table("org_membership")
data class OrgMembershipEntity(
    @Id val id: Long? = null,
    @Column("user_id") val userId: Long,
    @Column("org_id") val orgId: Long,
    @Column("org_role") val orgRole: String,
    val status: String = "active",
    @Column("created_at") val createdAt: Instant,
)
