package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `release`：发布标记真源（repo_id + path → 已发布的 spec_hash）。 */
@Table("release_marker")
data class ReleaseEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    val path: String,
    @Column("spec_hash") val specHash: String,
    @Column("released_at") val releasedAt: Instant,
)
