package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface ProjectRepository : CrudRepository<ProjectEntity, Long> {
    fun findByName(name: String): ProjectEntity?
}
