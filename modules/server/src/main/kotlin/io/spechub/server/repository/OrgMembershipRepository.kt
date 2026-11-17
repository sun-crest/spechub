package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface OrgMembershipRepository : CrudRepository<OrgMembershipEntity, Long> {
    fun findByUserIdAndOrgId(
        userId: Long,
        orgId: Long,
    ): OrgMembershipEntity?

    fun findAllByUserId(userId: Long): List<OrgMembershipEntity>

    fun findAllByOrgIdOrderByUserIdAsc(orgId: Long): List<OrgMembershipEntity>

    /** 集合式删除：删除用户前清理组织成员关系（FK RESTRICT）。 */
    fun deleteAllByUserId(userId: Long): Int
}
