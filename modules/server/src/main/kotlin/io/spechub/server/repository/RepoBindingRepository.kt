package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface RepoBindingRepository : CrudRepository<RepoBindingEntity, Long> {
    fun findAllByRepoIdOrderByIdAsc(repoId: Long): List<RepoBindingEntity>

    fun findByRepoIdAndStatus(
        repoId: Long,
        status: String,
    ): RepoBindingEntity?

    fun countByRepoIdAndStatus(
        repoId: Long,
        status: String,
    ): Long
}
