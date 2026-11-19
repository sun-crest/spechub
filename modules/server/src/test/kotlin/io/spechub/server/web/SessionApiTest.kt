package io.spechub.server.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.session.data.redis.RedisIndexedSessionRepository
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
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C3：Spring Session 会话生命周期（Cookie / CSRF / 登出 / 空闲过期 / Bearer 豁免）。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=session-api-token",
    ],
)
class SessionApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var sessionRepository: RedisIndexedSessionRepository

    @Autowired
    lateinit var sessionAuthenticationStrategy: SessionAuthenticationStrategy

    companion object {
        private const val ADMIN = "session-api-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-session-api-" + System.nanoTime())
        private val json = JsonMapper.builder().build()

        private fun uniqueSlug(): String =
            "sess-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(10)

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_session_api")
        }
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

    private fun login(client: HttpClient): Pair<String, String> {
        val response = send(client, "POST", "/api/auth/login", """{"token":"$ADMIN"}""")
        assertEquals(200, response.statusCode(), response.body())
        val csrf = json.readTree(response.body()).get("csrfToken").asString()
        val setCookie = response.headers().firstValue("Set-Cookie").orElse("")
        assertTrue(setCookie.contains("HttpOnly"), "会话 Cookie 必须 HttpOnly：$setCookie")
        assertTrue(setCookie.contains("SameSite=Lax"), "会话 Cookie SameSite=Lax：$setCookie")
        val cookieValue =
            assertNotNull(
                setCookie
                    .substringAfter("spechub_session=", "")
                    .substringBefore(";")
                    .takeIf { it.isNotBlank() },
                "应写入 spechub_session：$setCookie",
            )
        // DefaultCookieSerializer 默认 Base64 编码会话 ID
        val sessionId =
            String(
                Base64
                    .getDecoder()
                    .decode(cookieValue),
            )
        return csrf to sessionId
    }

    @Test
    fun `会话 Cookie CSRF 与登出幂等`() {
        val client = newClient()
        val (csrf, _) = login(client)

        val me = send(client, "GET", "/api/auth/me")
        assertEquals(200, me.statusCode(), me.body())
        assertEquals("session", json.readTree(me.body()).get("authMethod").asString())

        val slugMissing = uniqueSlug()
        val missing =
            send(client, "POST", "/api/projects", """{"name":"$slugMissing","slug":"$slugMissing"}""")
        assertEquals(403, missing.statusCode(), "缺 CSRF → 403")
        assertEquals("AUTH_CSRF", json.readTree(missing.body()).get("code").asString())

        val wrong =
            send(client, "POST", "/api/projects", """{"name":"$slugMissing","slug":"$slugMissing"}""", csrf = "wrong-token")
        assertEquals(403, wrong.statusCode(), "错误 CSRF → 403")

        val slugOk = uniqueSlug()
        val ok = send(client, "POST", "/api/projects", """{"name":"$slugOk","slug":"$slugOk"}""", csrf = csrf)
        assertEquals(201, ok.statusCode(), ok.body())

        // Bearer 链豁免 CSRF
        val bearerSlug = uniqueSlug()
        val bearer =
            send(
                newClient(),
                "POST",
                "/api/projects",
                """{"name":"$bearerSlug","slug":"$bearerSlug"}""",
                token = ADMIN,
            )
        assertEquals(201, bearer.statusCode(), bearer.body())

        assertEquals(204, send(client, "POST", "/api/auth/logout").statusCode())
        assertEquals(204, send(client, "POST", "/api/auth/logout").statusCode(), "登出幂等")
        assertEquals(401, send(client, "GET", "/api/auth/me").statusCode(), "登出后会话失效")
    }

    @Test
    fun `登录会话固定防护 - 会话 ID 变化`() {
        val request = MockHttpServletRequest()
        val session = MockHttpSession()
        request.session = session
        request.setRequestedSessionId(session.id)
        request.setRequestedSessionIdValid(true)
        val before = session.id
        val authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                "session-fixation-user",
                null,
                emptyList(),
            )
        sessionAuthenticationStrategy.onAuthentication(
            authentication,
            request,
            MockHttpServletResponse(),
        )
        assertTrue(before != session.id, "登录必须轮换会话 ID（会话固定防护）")
    }

    @Test
    fun `空闲超时后会话失效`() {
        val client = newClient()
        val (_, sessionId) = login(client)

        val session = assertNotNull(sessionRepository.findById(sessionId))
        session.setMaxInactiveInterval(Duration.ofSeconds(1))
        sessionRepository.save(session)

        Thread.sleep(1_500)
        assertEquals(401, send(client, "GET", "/api/auth/me").statusCode(), "空闲超时后 401")
    }
}
