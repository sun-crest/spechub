package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `comment`：接口/文档/仓库轻评论（api_id/doc_id 为展示快照，稳定寻址用 repo + path）。 */
@Table("comment")
data class CommentEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    @Column("org_id") val orgId: Long? = null,
    @Column("api_id") val apiId: Long? = null,
    @Column("doc_id") val docId: Long? = null,
    val method: String = "",
    val path: String = "",
    val author: String,
    val body: String,
    @Column("created_at") val createdAt: Instant,
)
