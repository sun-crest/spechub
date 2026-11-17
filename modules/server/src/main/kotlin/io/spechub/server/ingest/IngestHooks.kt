package io.spechub.server.ingest

import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.SubscriptionRecord

/**
 * 摄取回写端口：管理库读写以函数注入，Ingestor 可脱离 Spring 手动装配（kernel 级测试用默认空实现）。
 * 迁移中的管理域（repo 同步状态、release、订阅通知）都从这里回到管理库。
 */
class IngestHooks(
    val updateSyncStatus: (repoId: Long, status: String, error: String?) -> Unit = { _, _, _ -> },
    val replayReleases: (repoId: Long, projection: ProjectionStore) -> Unit = { _, _ -> },
    val subscriptionsForEndpoint: (repoId: Long, method: String, path: String) -> List<SubscriptionRecord> = { _, _, _ -> emptyList() },
    val addNotification: (team: String, repoId: Long, method: String, path: String, summary: String) -> Unit = { _, _, _, _, _ -> },
)
