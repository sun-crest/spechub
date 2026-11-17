package io.spechub.server.web

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R1 —— 统一错误契约（规范 §2.2/§2.3/§2.5）验收：
 * - 任何错误响应均带 `code` 且与状态码绑定；
 * - filter（TokenAuthFilter）与 advice 层（@RestControllerAdvice）错误体同构；
 * - 未知异常：响应体零内部细节（不泄露堆栈/异常消息），只进 ERROR 日志。
 *
 * 基建同 WebApiTest：RANDOM_PORT + JDK HttpClient + Jackson 3（Boot 4 下不用 MockMvc）。
 * 本测试启用鉴权（spechub.admin-token），以便覆盖 filter 的 401 分支。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.admin-token=test-contract-token",
        "spechub.polling-interval-ms=3600000",
    ],
)
@Import(TestBombController::class)
class ErrorContractTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    companion object {
        private val TEMPDIR: java.nio.file.Path = kotlin.io.path.createTempDirectory("spechub-error-test-" + System.nanoTime())
        private const val ADMIN = "test-contract-token"

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }

        private val json = JsonMapper.builder().build()
    }

    private val client = HttpClient.newHttpClient()

    private fun send(
        method: String,
        path: String,
        body: String?,
        token: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        path: String,
        token: String? = ADMIN,
    ) = send("GET", path, body = null, token = token)

    private fun post(
        path: String,
        body: String,
        token: String? = ADMIN,
    ) = send("POST", path, body, token)

    /** 断言错误体结构：status/code/message/instance 齐全，code 与状态绑定 */
    private fun assertErrorBody(
        bodyStr: String,
        expectedStatus: Int,
        expectedCode: String,
    ) {
        val t = json.readTree(bodyStr)
        assertEquals(expectedStatus, t.at("/status").asInt(), "status 应与 HTTP 一致：$bodyStr")
        assertEquals(expectedCode, t.at("/code").asText(), "code 应与语义映射一致：$bodyStr")
        assertTrue(t.at("/message").asText().isNotBlank(), "message 非空：$bodyStr")
        assertTrue(t.at("/instance").asText().isNotBlank(), "instance 非空：$bodyStr")
    }

    @Test
    fun `资源不存在 - 统一 NOT_FOUND 错误体`() {
        val r = get("/api/repos/999999")
        assertEquals(404, r.statusCode())
        assertErrorBody(r.body(), 404, "NOT_FOUND")
        assertEquals("/api/repos/999999", json.readTree(r.body()).at("/instance").asText())
    }

    @Test
    fun `请求体非法 JSON - MALFORMED_BODY`() {
        val r = post("/api/repos", "{not-json", token = ADMIN)
        assertEquals(400, r.statusCode())
        assertErrorBody(r.body(), 400, "MALFORMED_BODY")
    }

    @Test
    fun `未授令牌访问受保护端点 - filter 统一 401 错误体`() {
        val noToken = get("/api/repos", token = null)
        assertEquals(401, noToken.statusCode())
        assertErrorBody(noToken.body(), 401, "AUTH_UNAUTHORIZED")

        val bad = get("/api/repos", token = "wrong-token")
        assertEquals(401, bad.statusCode())
        assertEqualError(noToken.body(), bad.body(), "无 token 与错误 token 的错误体应同构")
    }

    @Test
    fun `mock 无匹配 - MOCK_NO_MATCH`() {
        val r = get("/mock/999/pets")
        assertEquals(404, r.statusCode())
        assertErrorBody(r.body(), 404, "MOCK_NO_MATCH")
    }

    @Test
    fun `未知异常 - 500 响应体脱敏不进栈`() {
        val r = get("/__test/bomb")
        assertEquals(500, r.statusCode())
        val body = r.body()
        assertErrorBody(body, 500, "INTERNAL")
        assertFalse(body.contains("SHOULD_NOT_LEAK"), "500 响应不得泄露异常内部细节：$body")
        assertFalse(body.contains("IllegalStateException"), "500 响应不得含异常类名：$body")
    }

    private fun assertEqualError(
        a: String,
        b: String,
        msg: String,
    ) {
        assertEquals(json.readTree(a).at("/code").asText(), json.readTree(b).at("/code").asText(), msg)
        assertEquals(json.readTree(a).at("/status").asInt(), json.readTree(b).at("/status").asInt(), msg)
    }
}

/** 测试专用：抛未预期异常以验证 500 响应体脱敏（响应不得含该标记字符串） */
@RestController
class TestBombController {
    @GetMapping("/__test/bomb")
    fun bomb(): String = throw IllegalStateException("SHOULD_NOT_LEAK-marker")
}
