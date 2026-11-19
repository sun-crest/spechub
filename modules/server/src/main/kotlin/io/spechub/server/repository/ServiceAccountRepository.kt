package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface ServiceAccountRepository : CrudRepository<ServiceAccountEntity, Long> {
    fun findByTokenHash(tokenHash: String): ServiceAccountEntity?

    fun findAllByProjectIdOrderByNameAsc(projectId: Long): List<ServiceAccountEntity>

    fun findByIdAndProjectId(
        id: Long,
        projectId: Long,
    ): ServiceAccountEntity?

    fun deleteAllByProjectId(projectId: Long): Int
}
