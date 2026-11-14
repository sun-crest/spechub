package io.spechub.server.web

import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.ingest.Ingestor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** E6 —— webhook 接收端点：内容变更触发增量摄取（托管适配器留作 P1 接入点） */
@RestController
@RequestMapping("/api")
class WebhookController(
    private val ingestor: Ingestor,
    private val index: SpecIndexStore,
    private val urlTokens: UrlTokenStore,
) {

    @PostMapping("/webhooks/{repoId}")
    fun trigger(@PathVariable repoId: Long): Ingestor.IngestResult {
        val repo = index.getRepo(repoId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $repoId 不存在")
        return ingestor.ingest(repo, urlTokens.get(repoId))
    }
}