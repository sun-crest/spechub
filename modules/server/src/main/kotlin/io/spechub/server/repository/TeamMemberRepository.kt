package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface TeamMemberRepository : CrudRepository<TeamMemberEntity, Long> {
    fun findAllByTeamIdOrderByRoleAscUserIdAsc(teamId: Long): List<TeamMemberEntity>

    fun findAllByUserId(userId: Long): List<TeamMemberEntity>

    fun findByTeamIdAndUserId(
        teamId: Long,
        userId: Long,
    ): TeamMemberEntity?

    fun countByTeamId(teamId: Long): Long

    fun countByTeamIdAndRole(
        teamId: Long,
        role: String,
    ): Long

    fun deleteAllByTeamId(teamId: Long)

    fun deleteAllByUserId(userId: Long)
}
