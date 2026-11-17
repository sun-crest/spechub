package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `repo`（Phase 2：仓库注册迁入；投影层只用 repoId 逻辑引用）。 */
@Table("repo")
data class RepoEntity(
    @Id val id: Long? = null,
    @Column("project_id") val projectId: Long,
    val url: String,
    val branch: String,
    @Column("sync_status") val syncStatus: String,
    @Column("last_error") val lastError: String? = null,
    @Column("created_at") val createdAt: Instant,
)
