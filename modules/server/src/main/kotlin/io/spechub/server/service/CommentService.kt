package io.spechub.server.service

import io.spechub.server.repository.CommentEntity
import io.spechub.server.repository.CommentRepository
import io.spechub.spec.index.CommentRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 评论域 Service（Phase 2）：从投影库迁入管理库。
 *
 * - 接口评论用 repo + method + path 稳定寻址；文档评论用 repo + path（method=''）。
 * - api_id / doc_id 仅作展示快照，不作为级联依据。
 */
@Service
class CommentService(
    private val comments: CommentRepository,
) {
    fun add(
        repoId: Long,
        apiId: Long?,
        docId: Long?,
        method: String,
        path: String,
        author: String,
        body: String,
    ): CommentRecord {
        val saved =
            comments.save(
                CommentEntity(
                    repoId = repoId,
                    apiId = apiId,
                    docId = docId,
                    method = method,
                    path = path,
                    author = author,
                    body = body,
                    createdAt = Instant.now(),
                ),
            )
        return saved.toRecord()
    }

    fun listForEndpoint(
        repoId: Long,
        method: String,
        path: String,
    ): List<CommentRecord> = comments.forEndpoint(repoId, method, path).map { it.toRecord() }

    fun listForRepo(
        repoId: Long,
        limit: Int = 100,
    ): List<CommentRecord> = comments.latestForRepo(repoId, limit).map { it.toRecord() }

    fun listForDoc(
        repoId: Long,
        path: String,
    ): List<CommentRecord> = comments.forDoc(repoId, path).map { it.toRecord() }

    fun get(id: Long): CommentRecord? = comments.findById(id).orElse(null)?.toRecord()

    @Transactional
    fun delete(id: Long) {
        comments.deleteById(id)
    }

    private fun CommentEntity.toRecord(): CommentRecord =
        CommentRecord(
            id = id,
            repoId = repoId,
            apiId = apiId,
            docId = docId,
            method = method,
            path = path,
            author = author,
            body = body,
            createdAt = createdAt.toString(),
        )
}
