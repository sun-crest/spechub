package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface ProjectRepository : CrudRepository<ProjectEntity, Long> {
    fun findByName(name: String): ProjectEntity?

    fun findByOrgIdAndSlug(
        orgId: Long,
        slug: String,
    ): ProjectEntity?

    fun findAllByOrgIdOrderByIdAsc(orgId: Long): List<ProjectEntity>

    fun findAllByOrderByIdAsc(): List<ProjectEntity>

    fun findAllByOwnerId(ownerId: Long): List<ProjectEntity>

    fun countByOrgId(orgId: Long): Long
}
