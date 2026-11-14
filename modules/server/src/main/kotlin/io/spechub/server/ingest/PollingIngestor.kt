package io.spechub.server.ingest

import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.config.SpecHubProperties
import io.spechub.server.web.UrlTokenStore
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** E7 —— 轮询兜底：未配 webhook 的仓定时 fetch + 摄取；复用 Ingestor 幂等，间隔可配 */
@Component
class PollingIngestor(
    private val ingestor: Ingestor,
    private val index: SpecIndexStore,
    private val urlTokens: UrlTokenStore,
    private val props: SpecHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${spechub.polling-interval-ms:60000}")
    fun poll() {
        val repos = index.listRepos()
        if (repos.isEmpty()) return
        log.info("轮询摄取：{} 个仓", repos.size)
        for (repo in repos) {
            try {
                ingestor.ingest(repo, urlTokens.get(repo.id))
            } catch (e: Exception) {
                log.warn("轮询摄取失败 repo#{} {}：{}", repo.id, repo.url, e.message?.take(200))
            }
        }
    }
}