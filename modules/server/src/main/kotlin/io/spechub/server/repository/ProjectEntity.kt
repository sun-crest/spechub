package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `project`（Phase 2：从投影库迁入）。 */
@Table("project")
data class ProjectEntity(
    @Id val id: Long? = null,
    val name: String,
    @Column("created_at") val createdAt: Instant,
)
