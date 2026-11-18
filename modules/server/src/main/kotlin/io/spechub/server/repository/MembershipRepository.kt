package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface MembershipRepository : CrudRepository<MembershipEntity, Long> {
    fun findByUserIdAndProjectId(
        userId: Long,
        projectId: Long,
    ): MembershipEntity?

    fun findAllByUserId(userId: Long): List<MembershipEntity>

    /** 集合式删除：一条 `DELETE ... WHERE user_id=?`，勿在循环里逐条 delete（编码规范 §5.1） */
    fun deleteAllByUserId(userId: Long): Int

    fun findAllByProjectId(projectId: Long): List<MembershipEntity>

    /** 删除项目前清理成员关系（FK RESTRICT）；集合式删除。 */
    fun deleteAllByProjectId(projectId: Long): Int

    /** 移除单个项目成员。 */
    fun deleteByUserIdAndProjectId(
        userId: Long,
        projectId: Long,
    ): Int
}
