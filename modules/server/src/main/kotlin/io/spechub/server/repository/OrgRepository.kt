package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface OrgRepository : CrudRepository<OrgEntity, Long> {
    fun findBySlug(slug: String): OrgEntity?

    fun findAllByOrderByIdAsc(): List<OrgEntity>
}
