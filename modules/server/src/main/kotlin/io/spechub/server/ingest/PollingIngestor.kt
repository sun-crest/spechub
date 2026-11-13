package io.spechub.server.ingest

import io.spechub.server.store.IndexService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * E7 轮询兜底：未配 webhook 的仓定时 fetch + diff（内容变更触发增量摄取）。
 * 幂等由 spec-index 保证（同 hash 短路），因此无需维护游标——每轮全量 diff 驱动。
 * 轮询间隔走配置 `spechub.poll.interval-ms`（缺省 60s）。
 */
@Service
class PollingIngestor(
    private val index: IndexService,
    private val ingestor: Ingestor,
) {
    private val log = LoggerFactory.getLogger(PollingIngestor::class.java)

    @Scheduled(fixedDelayString = "\${spechub.poll.interval-ms:60000}")
    fun pollAll() {
        val repos = index.store.listRepos()
        if (repos.isEmpty()) return
        var changed = 0
        for (rp in repos) {
            val repo = rp.repo
            try {
                val result = ingestor.ingest(
                    project = rp.project.name,
                    org = repo.org,
                    name = repo.name,
                    remoteUrl = repo.remoteUrl,
                    branch = repo.branch,
                    source = "poll",
                )
                if (!result.unchanged) changed++
            } catch (e: Exception) {
                log.warn("轮询摄取 [{}/{}] 失败: {}", repo.org, repo.name, e.message)
            }
        }
        if (changed > 0) log.info("轮询完成：{} 个 repo 内容变更已摄取", changed)
    }
}