package io.spechub.server.web

import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.model.Operation
import io.spechub.spec.model.io.SpecModelIO
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/*
 * I1 —— 在线调试 Try it out：读 spec 构造请求，向后端任意 baseUrl 发真实请求。
 *
 * 口径（用户拍板 2026-11-15）：允许任意 http(s) baseUrl（类 Postman，UI 明示目标由使用者负责）；
 * 后端只做三件事：协议白名单（http/https）、超时（连接 5s + 读取 10s）、响应体截断（256KB）。
 * 无副作用写入 spec，属只读调试能力；不受「写操作皆审」约束。
 */
@RestController
@RequestMapping("/api")
class TryController(private val index: SpecIndexStore) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class TryRequest(
        val baseUrl: String,
        val pathParams: Map<String, String> = emptyMap(),
        val queryParams: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    )

    data class TryResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String,
        val truncated: Boolean,
        val durationMs: Long,
    )

    @PostMapping("/apis/{id}/try")
    fun tryIt(@PathVariable id: Long, @RequestBody req: TryRequest): TryResponse {
        val api = index.getApiById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "api $id 不存在")

        val base = req.baseUrl.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, "baseUrl 仅允许 http(s) 协议")
        }

        var path = api.path
        for ((k, v) in req.pathParams) path = path.replace("{$k}", v)
        val target = buildUrl(base, path, req.queryParams)

        val builder = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
        for ((k, v) in req.headers) builder.header(k, v)
        if (req.body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(api.method, HttpRequest.BodyPublishers.ofString(req.body))
        } else {
            builder.method(api.method, HttpRequest.BodyPublishers.noBody())
        }

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val started = System.nanoTime()
        val resp = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: Exception) {
            log.warn("try 请求目标失败 {}：{}", target, e.message)
            throw ApiException(ApiErrorCode.UPSTREAM_ERROR, "请求目标失败，请检查 baseUrl")
        }

        val truncatedBody = resp.body().readNBytes(MAX_BODY + 1)
        val truncated = truncatedBody.size > MAX_BODY
        val trimmed = truncatedBody.copyOf(truncatedBody.size.coerceAtMost(MAX_BODY))
        val bodyText = String(trimmed, StandardCharsets.UTF_8)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val headers = resp.headers().map().entries.associate { it.key to it.value }
        return TryResponse(resp.statusCode(), headers, bodyText, truncated, elapsedMs)
    }

    private fun buildUrl(base: String, path: String, query: Map<String, String>): String {
        val sb = StringBuilder(base.trimEnd('/')).append(path)
        if (query.isNotEmpty()) {
            sb.append(if (path.contains('?')) '&' else '?')
            sb.append(query.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
            })
        }
        return sb.toString()
    }

    companion object {
        /** 响应体截断上限（256KB，防调试打爆内存） */
        const val MAX_BODY = 256 * 1024
    }
}