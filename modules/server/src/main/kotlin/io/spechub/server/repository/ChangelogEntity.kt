package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `changelog`：写操作审计留痕（repo/api 均可空，api_id 为快照）。 */
@Table("changelog")
data class ChangelogEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long? = null,
    @Column("api_id") val apiId: Long? = null,
    val actor: String,
    val action: String,
    val detail: String,
    @Column("created_at") val createdAt: Instant,
)
