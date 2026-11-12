package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Webhook 接收（E6）：Gitea push 事件 → 触发增量摄取。
 * 端点按 Gitea 惯例设计：`POST /hooks/gitea`，payload 头部 `X-Gitea-Event`, body 含 repo/ref。
 * P0 不验签；凭据注入留待 E2 收口。
 */
@RestController
@RequestMapping("/hooks")
class WebhookController(private val ingestor: Ingestor) {

    data class GiteaEvent(
        val ref: String? = null,
        val repository: GiteaRepo? = null,
    ) {
        data class GiteaRepo(
            val full_name: String? = null,
            val clone_url: String? = null,
        )
    }

    @PostMapping("/gitea")
    fun onPush(@RequestBody event: GiteaEvent): ResponseEntity<Map<String, Any>> {
        val full = event.repository?.full_name ?: return ResponseEntity.badRequest().body(mapOf<String, Any>("ok" to false, "error" to "missing repository.full_name"))
        val cloneUrl = event.repository.clone_url ?: return ResponseEntity.badRequest().body(mapOf<String, Any>("ok" to false, "error" to "missing repository.clone_url"))
        val parts = full.split("/")
        if (parts.size != 2) return ResponseEntity.badRequest().body(mapOf<String, Any>("ok" to false, "error" to "full_name 应为 org/repo"))

        // 项目名 = 平台默认项目（P1 多项目收口）；webhook 无法携带项目，统一用 org 作 project 简化
        val result = try {
            ingestor.ingest(
                project = parts[0], org = parts[0], name = parts[1],
                remoteUrl = cloneUrl, source = "webhook",
            )
        } catch (e: Exception) {
            return ResponseEntity.status(422).body(mapOf<String, Any>("ok" to false, "error" to (e.message ?: "ingest failed")))
        }
        val specFile: Any = result.specFile ?: "none"
        return ResponseEntity.ok(mapOf<String, Any>("ok" to true, "unchanged" to result.unchanged, "specFile" to specFile))
    }
}