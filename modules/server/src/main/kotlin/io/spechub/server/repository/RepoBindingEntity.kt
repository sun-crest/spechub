package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `repo_binding`：逻辑仓库的真源位置。同一 repo 至多一条 `active`。 */
@Table("repo_binding")
data class RepoBindingEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    val kind: String,
    @Column("connection_id") val connectionId: Long? = null,
    @Column("external_repo_id") val externalRepoId: String? = null,
    @Column("remote_url") val remoteUrl: String,
    val branch: String = "main",
    val status: String = "candidate",
    @Column("created_at") val createdAt: Instant,
    @Column("retired_at") val retiredAt: Instant? = null,
) {
    companion object {
        const val KIND_MANAGED = "managed"
        const val KIND_EXTERNAL = "external"
        const val STATUS_CANDIDATE = "candidate"
        const val STATUS_ACTIVE = "active"
        const val STATUS_RETIRED = "retired"
    }
}
