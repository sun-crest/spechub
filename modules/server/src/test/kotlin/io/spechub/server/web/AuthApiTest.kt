package io.spechub.server.web

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H6 —— admin token 登录占位（§7 O3）：
 * - 配置 `spechub.admin-token` 后，`/api/auth/status` 报告启用；未带 Bearer 访问受保护端点应 401。
 * - `POST /api/auth/login` 校验 token；错误令牌 401，正确令牌返回 authenticated=true。
 * - auth 端点自身放行（不要求登录）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.git-source-dir=build/tmp/authtest/gitsource",
        "spechub.sqlite-dir=build/tmp/authtest/sqlite",
        "spechub.artifacts-dir=build/tmp/authtest/artifacts",
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest {

    @LocalServerPort
    var port: Int = 0

    companion object {
        private val json = JsonMapper.builder().build()
    }

    private val client = HttpClient.newHttpClient()

    private fun req(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) b.header("Content-Type", "application/json")
        if (token != null) b.header("Authorization", "Bearer $token")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 干净测床：保证 auth 相关测试与是否注册过仓无关（只验鉴权与放行） */
    @BeforeAll
    fun warmup() {
        // 触发 context 即可；无仓也不影响鉴权语义
        assertEquals(200, req("GET", "/api/auth/status").statusCode())
    }

    @Test
    fun `status 报告启用且未认证`() {
        val r = req("GET", "/api/auth/status")
        assertEquals(200, r.statusCode(), r.body())
        val tree = json.readTree(r.body())
        assertTrue(tree.get("authEnabled").asBoolean(), "配置了 admin-token 应报告启用")
        assertEquals(false, tree.get("authenticated").asBoolean(), "未带 token 应为未认证")
    }

    @Test
    fun `未带 token 访问受保护端点返回 401`() {
        val r = req("GET", "/api/projects")
        assertEquals(401, r.statusCode(), r.body())
        // /actuator 放行（compose healthcheck 兼容）
        assertEquals(200, req("GET", "/actuator/health").statusCode())
    }

    @Test
    fun `login 正确令牌通过 错误令牌 401`() {
        // 正确的 token（与 admin-token 属性一致）
        val ok = req("POST", "/api/auth/login", body = """{"token":"test-admin-token-123"}""")
        assertEquals(200, ok.statusCode(), ok.body())
        assertTrue(json.readTree(ok.body()).get("authenticated").asBoolean())

        // 错误的 token
        val bad = req("POST", "/api/auth/login", body = """{"token":"wrong-token"}""")
        assertEquals(401, bad.statusCode(), bad.body())
    }

    @Test
    fun `带正确 token 可访问受保护端点并报告已认证`() {
        val projects = req("GET", "/api/projects", token = "test-admin-token-123")
        assertEquals(200, projects.statusCode(), projects.body())

        val status = req("GET", "/api/auth/status", token = "test-admin-token-123")
        assertTrue(json.readTree(status.body()).get("authenticated").asBoolean(), "带正确 token 应报告已认证")
    }
}