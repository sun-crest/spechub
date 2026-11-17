package io.spechub.server.service

import io.spechub.server.repository.ReviewEntity
import io.spechub.server.repository.ReviewRepository
import io.spechub.spec.index.ReviewRecord
import io.spechub.spec.index.ReviewStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 评审域 Service（Phase 2）：从投影库迁入管理库。
 *
 * - `create` 为写回入口（pending）；`resolve` 记录审批结果与时间。
 * - 与 git 写回（SpecWriter）不在同一事务：审批编排由 Controller 按固定次序执行。
 */
@Service
class ReviewService(
    private val reviews: ReviewRepository,
) {

    fun create(repoId: Long, path: String, newContent: String, diff: String, oldHash: String?, requester: String): ReviewRecord {
        val saved = reviews.save(
            ReviewEntity(
                repoId = repoId, path = path, newContent = newContent, diff = diff,
                oldHash = oldHash, status = ReviewStatus.PENDING, requester = requester, createdAt = Instant.now(),
            ),
        )
        return saved.toRecord()
    }

    fun get(id: Long): ReviewRecord? = reviews.findById(id).orElse(null)?.toRecord()

    fun list(repoId: Long? = null, status: String? = null): List<ReviewRecord> {
        val entities = when {
            repoId != null && status != null -> reviews.findByRepoIdAndStatus(repoId, status)
            repoId != null -> reviews.findByRepoId(repoId)
            status != null -> reviews.findByStatus(status)
            else -> reviews.findAllOrdered()
        }
        return entities.map { it.toRecord() }
    }

    /**
     * 审批状态机：条件更新（仅 pending 可迁移），返回是否成功。
     * 并发审批时后到者返回 false，由调用方映射 409，避免重复落真源。
     */
    @Transactional
    fun resolve(id: Long, status: String, reviewer: String, rejectReason: String? = null): Boolean {
        val now = Instant.now()
        val updated = if (rejectReason == null) {
            reviews.resolveIfPending(id, status, reviewer, now)
        } else {
            reviews.rejectIfPending(id, status, reviewer, rejectReason, now)
        }
        return updated > 0
    }

    private fun ReviewEntity.toRecord(): ReviewRecord = ReviewRecord(
        id = id,
        repoId = repoId,
        path = path,
        newContent = newContent,
        diff = diff,
        oldHash = oldHash,
        status = status,
        requester = requester,
        reviewer = reviewer,
        rejectReason = rejectReason,
        createdAt = createdAt.toString(),
        resolvedAt = resolvedAt?.toString(),
    )
}
