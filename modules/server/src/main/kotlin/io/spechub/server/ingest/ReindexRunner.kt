package io.spechub.server.ingest

import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.web.UrlTokenStore
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** E5 —— 全量重扫命令：`--reindex` 验证「索引可重建」纪律（§6.4 决策 7） */
@Component
class ReindexRunner(
    private val ingestor: Ingestor,
    private val index: SpecIndexStore,
    private val urlTokens: UrlTokenStore,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (args.sourceArgs.none { it == "--reindex" }) return
        val repos = index.listRepos()
        if (repos.isEmpty()) {
            log.info("reindex：无注册仓，跳过")
            return
        }
        log.info("reindex 开始：{} 个仓全量重扫", repos.size)
        var errors = 0
        for (repo in repos) {
            try {
                val result = ingestor.ingest(repo, urlTokens.get(repo.id))
                log.info("  repo#{} {} → api={} schema={} version={} messages={}",
                    repo.id, repo.url, result.apiCount, result.schemaCount, result.versionCount, result.messages.size)
            } catch (e: Exception) {
                errors++
                log.warn("  repo#{} {} 失败：{}", repo.id, repo.url, e.message?.take(200))
            }
        }
        log.info("reindex 完成：{} 个仓，{} 个失败（索引仍可重建）", repos.size, errors)
    }
}