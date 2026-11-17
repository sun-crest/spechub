package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface SubscriptionRepository : CrudRepository<SubscriptionEntity, Long> {
    fun findByRepoIdAndMethodAndPathAndTeam(
        repoId: Long,
        method: String,
        path: String,
        team: String,
    ): SubscriptionEntity?

    @Query("SELECT * FROM subscription WHERE repo_id = :repoId ORDER BY team, path")
    fun findByRepoId(repoId: Long): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription ORDER BY team, path")
    fun findAllOrdered(): List<SubscriptionEntity>

    fun findAllByRepoIdIn(repoIds: Collection<Long>): List<SubscriptionEntity>

    @Query("SELECT * FROM subscription WHERE repo_id = :repoId AND method = :method AND path = :path ORDER BY team")
    fun forEndpoint(
        repoId: Long,
        method: String,
        path: String,
    ): List<SubscriptionEntity>
}
