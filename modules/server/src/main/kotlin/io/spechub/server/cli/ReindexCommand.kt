package io.spechub.server.cli

import io.spechub.server.ingest.Ingestor
import io.spechub.server.store.IndexService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * E5 全量重建 `reindex`：遍历已注册 repo，逐个重新摄取（验证"索引可重建"纪律，§6.4 决策 7）。
 * 用法：`SPECHUB_REINDEX=1 ./spechub` 或启动参数 `--spechub.reindex=true`
 */
@Component
class ReindexCommand(
    private val index: IndexService,
    private val ingestor: Ingestor,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(ReindexCommand::class.java)

    override fun run(args: ApplicationArguments) {
        val reindex = argsNonOptionArgs(args).any { it == "--reindex" } || System.getenv("SPECHUB_REINDEX") == "1"
        if (!reindex) return

        val repos = index.store.listRepos()
        log.info("reindex 开始：{} 个已注册 repo", repos.size)
        repos.forEach { rp ->
            val repo = rp.repo
            try {
                val result = ingestor.ingest(
                    project = rp.project.name,
                    org = repo.org,
                    name = repo.name,
                    remoteUrl = repo.remoteUrl,
                    branch = repo.branch,
                    source = "reindex",
                )
                log.info("  [{}/{}] unchanged={} specFile={}", repo.org, repo.name, result.unchanged, result.specFile)
            } catch (e: Exception) {
                log.error("  [{}/{}] 重建失败: {}", repo.org, repo.name, e.message)
            }
        }
        // 命令行模式跑完即退出：供 compose 一次性任务 / cron 使用
        System.exit(0)
    }

    private fun argsNonOptionArgs(args: ApplicationArguments): List<String> =
        args.nonOptionArgs + args.sourceArgs.filter { it.startsWith("--") && !it.contains("=") }
}