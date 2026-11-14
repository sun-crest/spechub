package io.spechub.server.web

import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.ingest.Ingestor
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/*
 * E2 —— Repo 注册 REST：添加仓地址 / 分支 / 可选 token 注入（不落库）。
 * 注册即触发一次摄取（同步返回 api 统计），供立即验收；后续增量走 webhook（E6）/ 轮询（E7）。
 */

@RestController
@RequestMapping("/api")
class RepoController(
    private val ingestor: Ingestor,
    private val index: SpecIndexStore,
    private val urlTokens: UrlTokenStore,
) {

    data class RegisterRepoRequest(
        val url: String,
        val branch: String? = null,
        val token: String? = null,
    )

    data class RegisterResponse(
        val repo: RepoRecord,
        val apiCount: Int,
        val schemaCount: Int,
        val versionCount: Int,
        val messages: List<String>,
    )

    @PostMapping("/repos")
    fun register(@RequestBody req: RegisterRepoRequest): RegisterResponse {
        if (req.url.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "url 不能为空")
        val repo = index.insertRepo(DEFAULT_PROJECT, req.url.trim(), req.branch?.ifEmpty { null } ?: "main")
        if (req.token != null && req.token.isNotBlank()) {
            urlTokens.put(repo.id, injectToken(req.url.trim(), req.token.trim()))
        }
        val result = ingestor.ingest(repo, urlTokens.get(repo.id))
        return RegisterResponse(repo, result.apiCount, result.schemaCount, result.versionCount, result.messages)
    }

    @GetMapping("/repos")
    fun list(): List<RepoRecord> = index.listRepos()

    @GetMapping("/repos/{id}")
    fun get(@PathVariable id: Long): RepoRecord =
        index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")

    @GetMapping("/repos/{id}/apis")
    fun apis(
        @PathVariable id: Long,
        @RequestParam method: String? = null,
        @RequestParam tag: String? = null,
        @RequestParam q: String? = null,
    ) = index.listApis(id, method?.uppercase(), tag, q)

    companion object {
        const val DEFAULT_PROJECT = "default"

        /** `https://host/path` → `https://oauth2:TOKEN@host/path`（仅内存使用） */
        internal fun injectToken(url: String, token: String): String =
            if (url.contains("://")) {
                val proto = url.substringBefore("://")
                val rest = url.substringAfter("://")
                if (rest.contains("@")) url else "$proto://oauth2:$token@$rest"
            } else {
                url
            }
    }
}