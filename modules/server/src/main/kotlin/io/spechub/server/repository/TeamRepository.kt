package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface TeamRepository : CrudRepository<TeamEntity, Long> {
    fun findAllByOrgIdOrderByNameAsc(orgId: Long): List<TeamEntity>

    fun findByIdAndOrgId(
        id: Long,
        orgId: Long,
    ): TeamEntity?

    /** 团队名唯一性忽略大小写（PostgreSQL 默认大小写敏感；MySQL collation 已是 ci）。 */
    @Query("SELECT * FROM team WHERE org_id = :orgId AND LOWER(name) = LOWER(:name)")
    fun findByOrgIdAndNameIgnoreCase(
        orgId: Long,
        name: String,
    ): TeamEntity?
}
