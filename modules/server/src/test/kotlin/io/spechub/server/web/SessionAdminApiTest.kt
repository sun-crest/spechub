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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C4：活动会话列表 / 吊销（自己 + org_admin 运维）、脱敏 id、当前会话标记。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=session-admin-token",
    ],
)
class SessionAdminApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    companion object {
        private const val ADMIN = "session-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-session-admin-" + System.nanoTime())
        private val json = JsonMapper.builder().build()

        private fun unique(prefix: String): String =
            "$prefix-" +
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
            TestManagementDb.register(registry, "t_session_admin")
        }
    }

    private fun client(): HttpClient =
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
        val builder =
            HttpRequest
                .newBuilder(URI("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
                .header("User-Agent", "spechub-test-agent")
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (csrf != null) builder.header("X-CSRF-Token", csrf)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun createUser(): io.spechub.spec.index.UserRecord {
        val username = unique("sess")
        return users.createOrgUser(
            username = username,
            displayName = "",
            email = "$username@example.com",
            passwordHash = checkNotNull(passwordEncoder.encode("user-password-123")),
        )
    }

    private fun login(
        username: String,
        password: String = "user-password-123",
    ): Pair<HttpClient, String> {
        val client = client()
        val response = send(client, "POST", "/api/auth/login", """{"username":"$username","password":"$password"}""")
        assertEquals(200, response.statusCode(), response.body())
        return client to json.readTree(response.body()).get("csrfToken").asString()
    }

    @Test
    fun `自己的会话列表与吊销 - 脱敏 id 当前标记 不能吊销他人`() {
        val user = createUser()
        val other = createUser()
        val (clientA, csrfA) = login(user.username)
        val (clientB, _) = login(user.username)
        val (clientOther, _) = login(other.username)

        val sessions = send(clientA, "GET", "/api/auth/sessions")
        assertEquals(200, sessions.statusCode(), sessions.body())
        val list = json.readTree(sessions.body())
        assertEquals(2, list.size(), "同一用户两个会话：$list")
        val roots = (0 until list.size()).mapNotNull { list.get(it) }
        assertTrue(roots.all { it.get("id").asString().length == 16 }, "会话 id 脱敏（sha256 前 16 位）：$list")
        assertEquals(1, roots.count { it.get("current").asBoolean() }, "标记当前会话")
        assertTrue(roots.all { it.get("userAgent").asString() == "spechub-test-agent" })
        assertEquals("127.0.0.1", roots.first().get("ip").asString())

        val otherSessionId =
            roots.first { !it.get("current").asBoolean() }.get("id").asString()
        assertEquals(204, send(clientA, "DELETE", "/api/auth/sessions/$otherSessionId", csrf = csrfA).statusCode())
        assertEquals(401, send(clientB, "GET", "/api/auth/me").statusCode(), "被吊销会话下一次请求 401")

        val otherUserSession =
            json
                .readTree(send(clientOther, "GET", "/api/auth/sessions").body())
                .get(0)
                .get("id")
                .asString()
        assertEquals(
            404,
            send(clientA, "DELETE", "/api/auth/sessions/$otherUserSession", csrf = csrfA).statusCode(),
            "不能吊销他人会话",
        )
    }

    @Test
    fun `org_admin 查看与吊销指定用户会话`() {
        val user = createUser()
        val (clientA, _) = login(user.username)

        val userId = assertNotNull(user.id)
        val listed = send(client(), "GET", "/api/auth/users/$userId/sessions", token = ADMIN)
        assertEquals(200, listed.statusCode(), listed.body())
        val list = json.readTree(listed.body())
        assertEquals(1, list.size())
        assertTrue(!list.get(0).get("current").asBoolean(), "管理员查看他人会话不标记 current")
        val sessionId = list.get(0).get("id").asString()

        val revoked = send(client(), "DELETE", "/api/auth/users/$userId/sessions/$sessionId", token = ADMIN)
        assertEquals(204, revoked.statusCode(), revoked.body())
        assertEquals(401, send(clientA, "GET", "/api/auth/me").statusCode(), "管理员吊销后会话失效")
    }
}
