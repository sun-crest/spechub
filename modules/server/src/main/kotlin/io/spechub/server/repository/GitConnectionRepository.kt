package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface GitConnectionRepository : CrudRepository<GitConnectionEntity, Long> {
    fun findAllByOrgIdOrderByIdAsc(orgId: Long): List<GitConnectionEntity>
}
