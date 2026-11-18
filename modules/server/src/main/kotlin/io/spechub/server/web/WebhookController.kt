package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.service.RepoService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** E6 —— webhook 接收端点：内容变更触发增量摄取（托管适配器留作 P1 接入点） */
@RestController
@RequestMapping("/api")
class WebhookController(
    private val ingestor: Ingestor,
    private val repoService: RepoService,
    private val urlTokens: UrlTokenStore,
) {
    @PostMapping("/webhooks/{repoId}")
    fun trigger(
        @PathVariable repoId: Long,
    ): Ingestor.IngestResult {
        val repo =
            repoService.getRepo(repoId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")
        // Webhook 只接受 active binding 对应仓库的事件（ADR §4.4）
        if (repoService.sourceOf(repoId) == null) {
            throw ApiException(ApiErrorCode.CONFLICT, "repo $repoId 没有 active binding，拒绝摄取")
        }
        return ingestor.ingest(repo, urlTokens.get(repoId))
    }
}
