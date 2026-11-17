package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface ReleaseRepository : CrudRepository<ReleaseEntity, Long> {
    fun findByRepoIdAndPath(
        repoId: Long,
        path: String,
    ): ReleaseEntity?

    fun findAllByRepoId(repoId: Long): List<ReleaseEntity>
}
