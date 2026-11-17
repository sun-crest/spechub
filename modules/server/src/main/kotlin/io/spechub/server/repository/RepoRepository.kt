package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface RepoRepository : CrudRepository<RepoEntity, Long> {
    fun findByProjectIdAndUrl(projectId: Long, url: String): RepoEntity?
    fun findAllByOrderByIdAsc(): List<RepoEntity>
}
