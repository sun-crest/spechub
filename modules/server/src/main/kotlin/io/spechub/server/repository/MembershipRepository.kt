package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface MembershipRepository : CrudRepository<MembershipEntity, Long> {
    fun findByUserIdAndProjectId(userId: Long, projectId: Long): MembershipEntity?
    fun findAllByUserId(userId: Long): List<MembershipEntity>
    fun findAllByProjectId(projectId: Long): List<MembershipEntity>
}
