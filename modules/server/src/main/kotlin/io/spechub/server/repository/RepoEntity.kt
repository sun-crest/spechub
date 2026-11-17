package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 管理库 `repo`（Phase 2 迁入；阶段 A 补 org / connection / external id）。
 *
 * 真源位置由 `repo_binding` 表达；这里保留 `url` / `branch` 作为兼容展示与
 * `connection_id` 为空时的摄取兜底（见 ADR §4.4）。
 */
@Table("repo")
data class RepoEntity(
    @Id val id: Long? = null,
    @Column("project_id") val projectId: Long,
    @Column("org_id") val orgId: Long? = null,
    @Column("connection_id") val connectionId: Long? = null,
    @Column("external_repo_id") val externalRepoId: String? = null,
    val url: String,
    val branch: String,
    @Column("sync_status") val syncStatus: String,
    @Column("last_error") val lastError: String? = null,
    @Column("created_at") val createdAt: Instant,
)
