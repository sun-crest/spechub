package io.spechub.server.service

import io.spechub.server.repository.NotificationEntity
import io.spechub.server.repository.NotificationRepository
import io.spechub.server.repository.SubscriptionEntity
import io.spechub.server.repository.SubscriptionRepository
import io.spechub.spec.index.NotificationRecord
import io.spechub.spec.index.SubscriptionRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 订阅/通知域 Service（Phase 2）：从投影库迁入管理库。
 *
 * - `subscribe` 幂等（repo + method + path + team 唯一，先查后写）。
 * - 通知由 Ingestor 在检测到结构变化时写入（经 IngestHook 函数注入，避免 Ingestor 依赖 Spring Data）。
 * - 通知历史不随订阅删除而清空（供审计）。
 */
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionRepository,
    private val notifications: NotificationRepository,
) {
    @Transactional
    fun subscribe(
        repoId: Long,
        apiId: Long?,
        method: String,
        path: String,
        team: String,
        notifyUrl: String = "",
    ): SubscriptionRecord {
        val existing = subscriptions.findByRepoIdAndMethodAndPathAndTeam(repoId, method, path, team)
        val saved =
            if (existing != null) {
                subscriptions.save(existing.copy(apiId = apiId, notifyUrl = notifyUrl))
            } else {
                subscriptions.save(
                    SubscriptionEntity(
                        repoId = repoId,
                        apiId = apiId,
                        method = method,
                        path = path,
                        team = team,
                        notifyUrl = notifyUrl,
                        createdAt = Instant.now(),
                    ),
                )
            }
        return saved.toRecord()
    }

    fun list(repoId: Long? = null): List<SubscriptionRecord> =
        if (repoId != null) {
            subscriptions.findByRepoId(repoId).map { it.toRecord() }
        } else {
            subscriptions.findAllOrdered().map { it.toRecord() }
        }

    fun get(id: Long): SubscriptionRecord? = subscriptions.findById(id).orElse(null)?.toRecord()

    @Transactional
    fun unsubscribe(id: Long) {
        subscriptions.deleteById(id)
    }

    fun subscriptionsForEndpoint(
        repoId: Long,
        method: String,
        path: String,
    ): List<SubscriptionRecord> = subscriptions.forEndpoint(repoId, method, path).map { it.toRecord() }

    fun addNotification(
        team: String,
        repoId: Long,
        method: String,
        path: String,
        summary: String,
    ): NotificationRecord {
        val saved =
            notifications.save(
                NotificationEntity(team = team, repoId = repoId, method = method, path = path, summary = summary, createdAt = Instant.now()),
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

    private fun SubscriptionEntity.toRecord(): SubscriptionRecord =
        SubscriptionRecord(
            id = id,
            repoId = repoId,
            apiId = apiId,
            method = method,
            path = path,
            team = team,
            notifyUrl = notifyUrl,
            createdAt = createdAt.toString(),
        )

    private fun NotificationEntity.toRecord(): NotificationRecord =
        NotificationRecord(
            id = id,
            team = team,
            repoId = repoId,
            method = method,
            path = path,
            summary = summary,
            createdAt = createdAt.toString(),
        )
}
