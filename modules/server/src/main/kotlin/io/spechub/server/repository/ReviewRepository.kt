package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface ReviewRepository : CrudRepository<ReviewEntity, Long> {

    @Query("SELECT * FROM review ORDER BY id DESC")
    fun findAllOrdered(): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE repo_id = :repoId ORDER BY id DESC")
    fun findByRepoId(repoId: Long): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE status = :status ORDER BY id DESC")
    fun findByStatus(status: String): List<ReviewEntity>

    @Query("SELECT * FROM review WHERE repo_id = :repoId AND status = :status ORDER BY id DESC")
    fun findByRepoIdAndStatus(repoId: Long, status: String): List<ReviewEntity>
}
