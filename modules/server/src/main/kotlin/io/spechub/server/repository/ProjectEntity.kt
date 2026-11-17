package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 管理库 `project`（阶段 A：从「默认项目」升级为多项目）。
 *
 * `orgId` / `slug` / `ownerId` 在 V3 迁移中允许为空，由 ProjectService 写入路径补齐。
 */
@Table("project")
data class ProjectEntity(
    @Id val id: Long? = null,
    @Column("org_id") val orgId: Long? = null,
    val name: String,
    val slug: String? = null,
    val description: String = "",
    val visibility: String = "private",
    @Column("owner_id") val ownerId: Long? = null,
    val archived: Boolean = false,
    @Column("created_at") val createdAt: Instant,
)
