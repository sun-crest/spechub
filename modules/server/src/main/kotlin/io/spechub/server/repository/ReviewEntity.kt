package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `review`：写操作皆审（spec 写回先入 pending，人批准才落真源）。 */
@Table("review")
data class ReviewEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    val path: String,
    @Column("new_content") val newContent: String,
    val diff: String = "",
    @Column("old_hash") val oldHash: String? = null,
    val status: String = "pending",
    val requester: String,
    val reviewer: String? = null,
    @Column("reject_reason") val rejectReason: String? = null,
    @Column("created_at") val createdAt: Instant,
    @Column("resolved_at") val resolvedAt: Instant? = null,
)
