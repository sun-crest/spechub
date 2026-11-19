package io.spechub.server.web

import io.spechub.server.service.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C2：Spring Security 双链契约。
 * - Bearer 链：有效令牌 / 无 CSRF / 无效令牌 401；Web 链：Session + CSRF。
 * - 登录 → Spring Session Cookie（HttpOnly）→ `/me` 下发 CSRF → 写请求校验 CSRF。
 * - 登出吊销会话；安全头存在；所有环境默认拒绝。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=security-admin-token",
    ],
)
class SecurityConfigTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    companion object {
        private const val ADMIN = "security-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-security-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_security")
        }

        private val json = JsonMapper.builder().build()
    }

    private fun newClient(): HttpClient =
        HttpClient
            .newBuilder()
            .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build()

    private fun send(
        client: HttpClient,
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        csrf: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (csrf != null) builder.header("X-CSRF-Token", csrf)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun uniqueSlug(): String =
        "sec-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(10)

    @Test
    fun `Bearer 链 - 有效令牌可写且无需 CSRF 无效令牌 401`() {
        val client = newClient()
        val slug = uniqueSlug()
        val created =
            send(
                client,
                "POST",
                "/api/projects",
                """{"name":"$slug","slug":"$slug"}""",
                token = ADMIN,
            )
        assertEquals(201, created.statusCode(), created.body())

        val invalid = send(client, "GET", "/api/projects", token = "invalid-token")
        assertEquals(401, invalid.statusCode(), invalid.body())
        assertEquals("AUTH_UNAUTHORIZED", json.readTree(invalid.body()).get("code").asString())

        val noToken = send(client, "GET", "/api/projects")
        assertEquals(401, noToken.statusCode(), "所有环境默认拒绝")
    }

    @Test
    fun `Web 链 - 登录换 Session Cookie CSRF 与登出`() {
        val client = newClient()
        val login = send(client, "POST", "/api/auth/login", """{"token":"$ADMIN"}""")
        assertEquals(200, login.statusCode(), login.body())
        val loginTree = json.readTree(login.body())
        assertTrue(loginTree.get("authenticated").asBoolean())
        assertEquals("token", loginTree.get("authMethod").asString())
        assertTrue(loginTree.get("csrfToken").asString().isNotBlank())

        val setCookie = login.headers().firstValue("Set-Cookie").orElse("")
        assertTrue(setCookie.contains("spechub_session"), "应写会话 Cookie：$setCookie")
        assertTrue(setCookie.contains("HttpOnly"), "Cookie 必须 HttpOnly：$setCookie")

        val me = send(client, "GET", "/api/auth/me")
        assertEquals(200, me.statusCode(), me.body())
        val meTree = json.readTree(me.body())
        assertEquals("session", meTree.get("authMethod").asString())
        assertEquals("admin", meTree.get("username").asString())
        assertTrue(meTree.get("orgAdmin").asBoolean())
        val csrf = meTree.get("csrfToken").asString()
        assertTrue(csrf.isNotBlank(), "/me 应下发 CSRF token")
        assertEquals("nosniff", me.headers().firstValue("X-Content-Type-Options").orElse(""), "Spring Security 默认安全头")

        val slug1 = uniqueSlug()
        val noCsrf = send(client, "POST", "/api/projects", """{"name":"$slug1","slug":"$slug1"}""")
        assertEquals(403, noCsrf.statusCode(), "已认证 Web 写请求缺 CSRF 应 403")
        assertEquals("AUTH_CSRF", json.readTree(noCsrf.body()).get("code").asString())

        val slug2 = uniqueSlug()
        val withCsrf = send(client, "POST", "/api/projects", """{"name":"$slug2","slug":"$slug2"}""", csrf = csrf)
        assertEquals(201, withCsrf.statusCode(), withCsrf.body())

        val logout = send(client, "POST", "/api/auth/logout")
        assertEquals(204, logout.statusCode(), logout.body())
        assertEquals(401, send(client, "GET", "/api/auth/me").statusCode(), "登出后会话失效")
    }

    @Test
    fun `认证状态与登录失败契约`() {
        val client = newClient()
        val status = send(client, "GET", "/api/auth/status")
        assertEquals(200, status.statusCode(), status.body())
        val statusTree = json.readTree(status.body())
        assertTrue(statusTree.get("authEnabled").asBoolean(), "所有环境鉴权恒开")
        assertEquals(false, statusTree.get("authenticated").asBoolean())

        assertEquals(200, send(client, "GET", "/actuator/health").statusCode(), "actuator 放行")

        val bad = send(client, "POST", "/api/auth/login", """{"token":"wrong-token"}""")
        assertEquals(401, bad.statusCode(), bad.body())

        val both = send(client, "POST", "/api/auth/login", """{"token":"$ADMIN","password":"x"}""")
        assertEquals(400, both.statusCode(), "password 与 token 互斥")
    }

    @Test
    fun `密码登录 - Argon2id 校验通过并建立会话`() {
        val username =
            "pwd-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(10)
        users.createOrgUser(
            username = username,
            displayName = "Password User",
            passwordHash = passwordEncoder.encode("correct-password-123"),
        )
        val client = newClient()

        val ok = send(client, "POST", "/api/auth/login", """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(200, ok.statusCode(), ok.body())
        val tree = json.readTree(ok.body())
        assertTrue(tree.get("authenticated").asBoolean())
        assertEquals("password", tree.get("authMethod").asString())
        assertTrue(tree.get("csrfToken").asString().isNotBlank())
        assertEquals(200, send(client, "GET", "/api/auth/me").statusCode())

        val bad = send(newClient(), "POST", "/api/auth/login", """{"username":"$username","password":"wrong-password"}""")
        assertEquals(401, bad.statusCode(), bad.body())
        assertEquals("AUTH_UNAUTHORIZED", json.readTree(bad.body()).get("code").asString())
    }
}
