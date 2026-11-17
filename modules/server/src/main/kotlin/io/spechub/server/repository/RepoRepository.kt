package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface RepoRepository : CrudRepository<RepoEntity, Long> {
    fun findByProjectIdAndUrl(
        projectId: Long,
        url: String,
    ): RepoEntity?

    fun findAllByOrderByIdAsc(): List<RepoEntity>

    fun findAllByProjectIdIn(projectId: Collection<Long>): List<RepoEntity>

    fun findAllByOrgIdOrderByIdAsc(orgId: Long): List<RepoEntity>

    fun countByProjectId(projectId: Long): Long
}
