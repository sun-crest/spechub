package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface CommentRepository : CrudRepository<CommentEntity, Long> {
    /** 接口评论：repo + method + path 稳定寻址（投影重建后仍命中） */
    @Query("SELECT * FROM comment WHERE repo_id = :repoId AND method = :method AND path = :path ORDER BY id")
    fun forEndpoint(
        repoId: Long,
        method: String,
        path: String,
    ): List<CommentEntity>

    @Query("SELECT * FROM comment WHERE repo_id = :repoId ORDER BY id DESC LIMIT :limit")
    fun latestForRepo(
        repoId: Long,
        limit: Int,
    ): List<CommentEntity>

    /** 文档评论：repo + path 稳定寻址（method 为空串标识） */
    @Query("SELECT * FROM comment WHERE repo_id = :repoId AND path = :path AND method = '' ORDER BY id")
    fun forDoc(
        repoId: Long,
        path: String,
    ): List<CommentEntity>
}
