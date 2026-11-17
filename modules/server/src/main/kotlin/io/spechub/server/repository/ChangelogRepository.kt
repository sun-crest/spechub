package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface ChangelogRepository : CrudRepository<ChangelogEntity, Long> {
    @Query("SELECT * FROM changelog ORDER BY id DESC LIMIT :limit")
    fun findLatest(limit: Int): List<ChangelogEntity>

    @Query("SELECT * FROM changelog WHERE repo_id = :repoId ORDER BY id DESC LIMIT :limit")
    fun findByRepoId(
        repoId: Long,
        limit: Int,
    ): List<ChangelogEntity>

    @Query("SELECT * FROM changelog WHERE api_id = :apiId ORDER BY id DESC LIMIT :limit")
    fun findByApiId(
        apiId: Long,
        limit: Int,
    ): List<ChangelogEntity>

    @Query("SELECT * FROM changelog WHERE repo_id = :repoId AND api_id = :apiId ORDER BY id DESC LIMIT :limit")
    fun findByRepoIdAndApiId(
        repoId: Long,
        apiId: Long,
        limit: Int,
    ): List<ChangelogEntity>
}
