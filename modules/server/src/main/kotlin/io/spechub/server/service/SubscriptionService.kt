package io.spechub.server.service

import io.spechub.server.repository.NotificationEntity
import io.spechub.server.repository.NotificationRepository
import io.spechub.server.repository.RepoRepository
import io.spechub.server.repository.SubscriptionEntity
import io.spechub.server.repository.SubscriptionRepository
import io.spechub.server.repository.TeamRepository
import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.SubscriptionRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 订阅/通知域 Service（Phase 2 / B2）：从投影库迁入管理库。
 *
 * - `subscribe` 幂等（repo + method + path + team_id 唯一，先查后写）。
 * - B2 起写入路径以 `team_id` 为准：保留旧 `team` 入参做兼容，为空时经 `TeamService.resolveOrCreate` 解析/创建；
 *   `team` 列继续写名称快照。
 * - 通知由 Ingestor 在检测到结构变化时写入（经 IngestHook 函数注入，避免 Ingestor 依赖 Spring Data）。
 * - 通知历史不随订阅删除而清空（供审计）。
 */
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionRepository,
    private val notifications: NotificationRepository,
    private val repos: RepoRepository,
    private val teams: TeamRepository,
    private val teamService: TeamService,
) {
    @Transactional
    fun subscribe(
        repoId: Long,
        apiId: Long?,
        method: String,
        path: String,
        team: String = "",
        notifyUrl: String = "",
        teamId: Long? = null,
    ): SubscriptionRecord {
        val orgId = orgIdFor(repoId)
        val resolvedTeam =
            teamId?.let { id ->
                teamService.get(id, orgId) ?: throw IllegalArgumentException("团队 $id 不存在")
            } ?: teamService.resolveOrCreate(orgId, team)
        val resolvedTeamId = checkNotNull(resolvedTeam.id) { "team 必有 id" }
        val existing = subscriptions.findByRepoIdAndMethodAndPathAndTeamId(repoId, method, path, resolvedTeamId)
        val saved =
            if (existing != null) {
                subscriptions.save(existing.copy(apiId = apiId, notifyUrl = notifyUrl, team = resolvedTeam.name))
            } else {
                subscriptions.save(
                    SubscriptionEntity(
                        repoId = repoId,
                        orgId = orgId,
                        apiId = apiId,
                        method = method,
                        path = path,
                        teamId = resolvedTeamId,
                        team = resolvedTeam.name,
                        notifyUrl = notifyUrl,
                        createdAt = Instant.now(),
                    ),
                )
            }
        return saved.toRecord(resolvedTeam.name)
    }

    fun list(
        repoId: Long? = null,
        teamId: Long? = null,
    ): List<SubscriptionRecord> {
        val records =
            if (repoId != null) {
                toRecords(subscriptions.findByRepoId(repoId))
            } else {
                toRecords(subscriptions.findAllOrdered())
            }
        return if (teamId == null) records else records.filter { it.teamId == teamId }
    }

    /** 多 repo 过滤：空集合返回空，禁止退化为全量。 */
    fun list(
        repoIds: Collection<Long>,
        teamId: Long? = null,
    ): List<SubscriptionRecord> =
        if (repoIds.isEmpty()) {
            emptyList()
        } else {
            toRecords(subscriptions.findAllByRepoIdIn(repoIds))
                .filter { teamId == null || it.teamId == teamId }
                .sortedWith(compareBy({ it.teamName }, { it.path }))
        }

    fun get(id: Long): SubscriptionRecord? = subscriptions.findById(id).orElse(null)?.let { toRecords(listOf(it)).single() }

    @Transactional
    fun unsubscribe(id: Long) {
        subscriptions.deleteById(id)
    }

    fun subscriptionsForEndpoint(
        repoId: Long,
        method: String,
        path: String,
    ): List<SubscriptionRecord> = toRecords(subscriptions.forEndpoint(repoId, method, path))

    fun addNotification(
        teamId: Long,
        teamName: String,
        repoId: Long,
        method: String,
        path: String,
        summary: String,
    ): NotificationRecord {
        val saved =
            notifications.save(
                NotificationEntity(
                    teamId = teamId,
                    team = teamName,
                    repoId = repoId,
                    orgId = orgIdFor(repoId),
                    method = method,
                    path = path,
                    summary = summary,
                    createdAt = Instant.now(),
                ),
            )
        return saved.toRecord()
    }

    fun listNotifications(
        team: String? = null,
        limit: Int = 50,
    ): List<NotificationRecord> =
        if (team != null) {
            notifications.findLatestByTeam(team, limit).map { it.toRecord() }
        } else {
            notifications.findLatest(limit).map { it.toRecord() }
        }

    /** 多 repo 过滤（B4）：按 team_id 过滤；空集合返回空，禁止退化为全量。 */
    fun listNotifications(
        teamId: Long?,
        repoIds: Collection<Long>,
        limit: Int = 50,
    ): List<NotificationRecord> {
        if (repoIds.isEmpty()) return emptyList()
        val entities =
            if (teamId != null) {
                notifications.findAllByTeamIdAndRepoIdIn(teamId, repoIds)
            } else {
                notifications.findAllByRepoIdIn(repoIds)
            }
        return entities.sortedByDescending { it.id }.take(limit).map { it.toRecord() }
    }

    /** 旧文本团队过滤（保留给过渡期测试/调用方）。 */
    fun listNotificationsByTeamName(
        team: String?,
        repoIds: Collection<Long>,
        limit: Int = 50,
    ): List<NotificationRecord> {
        if (repoIds.isEmpty()) return emptyList()
        val entities =
            if (team != null) {
                notifications.findAllByTeamAndRepoIdIn(team, repoIds)
            } else {
                notifications.findAllByRepoIdIn(repoIds)
            }
        return entities.sortedByDescending { it.id }.take(limit).map { it.toRecord() }
    }

    private fun orgIdFor(repoId: Long): Long = checkNotNull(repos.findById(repoId).orElse(null)?.orgId) { "repo $repoId 未归属组织" }

    /** 批量取团队当前名，避免列表 N+1；历史行没有 team_id 时退回名称快照。 */
    private fun toRecords(entities: List<SubscriptionEntity>): List<SubscriptionRecord> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.mapNotNull { it.teamId }.distinct()
        val names =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                teams.findAllById(ids).mapNotNull { team -> team.id?.let { it to team.name } }.toMap()
            }
        return entities.map { it.toRecord(names[it.teamId] ?: it.team) }
    }

    private fun SubscriptionEntity.toRecord(teamName: String): SubscriptionRecord =
        SubscriptionRecord(
            id = id,
            repoId = repoId,
            apiId = apiId,
            method = method,
            path = path,
            teamId = teamId,
            teamName = teamName,
            team = teamName,
            notifyUrl = notifyUrl,
            createdAt = createdAt.toString(),
        )

    private fun NotificationEntity.toRecord(): NotificationRecord =
        NotificationRecord(
            id = id,
            teamId = teamId,
            teamName = team,
            team = team,
            repoId = repoId,
            method = method,
            path = path,
            summary = summary,
            createdAt = createdAt.toString(),
        )
}
