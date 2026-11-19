package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface NotificationRepository : CrudRepository<NotificationEntity, Long> {
    @Query("SELECT * FROM notification ORDER BY id DESC LIMIT :limit")
    fun findLatest(limit: Int): List<NotificationEntity>

    @Query("SELECT * FROM notification WHERE team = :team ORDER BY id DESC LIMIT :limit")
    fun findLatestByTeam(
        team: String,
        limit: Int,
    ): List<NotificationEntity>

    fun findAllByRepoIdIn(repoIds: Collection<Long>): List<NotificationEntity>

    fun findAllByTeamIdAndRepoIdIn(
        teamId: Long,
        repoIds: Collection<Long>,
    ): List<NotificationEntity>

    fun findAllByTeamIdInAndRepoIdIn(
        teamIds: Collection<Long>,
        repoIds: Collection<Long>,
    ): List<NotificationEntity>

    fun findAllByTeamAndRepoIdIn(
        team: String,
        repoIds: Collection<Long>,
    ): List<NotificationEntity>
}
