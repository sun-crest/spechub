package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant

interface ReviewRepository : CrudRepository<ReviewEntity, Long> {
    @Query("SELECT * FROM review ORDER BY id DESC")
    fun findAllOrdered(): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE repo_id = :repoId ORDER BY id DESC")
    fun findByRepoId(repoId: Long): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE status = :status ORDER BY id DESC")
    fun findByStatus(status: String): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE repo_id = :repoId AND status = :status ORDER BY id DESC")
    fun findByRepoIdAndStatus(
        repoId: Long,
        status: String,
    ): List<ReviewEntity>

    fun findAllByRepoIdIn(repoIds: Collection<Long>): List<ReviewEntity>

    fun findAllByRepoIdInAndStatus(
        repoIds: Collection<Long>,
        status: String,
    ): List<ReviewEntity>

    /** 审批状态机：仅 pending → 目标状态，返回影响行数（0 = 已被处理/不存在，竞态兜底） */
    @Modifying
    @Query(
        """
        UPDATE review SET status = :status, reviewer = :reviewer, resolved_at = :resolvedAt
        WHERE id = :id AND status = 'pending'
        """,
    )
    fun resolveIfPending(
        id: Long,
        status: String,
        reviewer: String,
        resolvedAt: Instant,
    ): Int

    @Modifying
    @Query(
        """
        UPDATE review SET status = :status, reviewer = :reviewer, reject_reason = :rejectReason, resolved_at = :resolvedAt
        WHERE id = :id AND status = 'pending'
        """,
    )
    fun rejectIfPending(
        id: Long,
        status: String,
        reviewer: String,
        rejectReason: String,
        resolvedAt: Instant,
    ): Int
}
