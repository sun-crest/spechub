package io.spechub.server.web

import io.spechub.server.repository.UserRepository
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

/** C3：密码登录（用户名 / 邮箱）、失败统一 401、连续失败 429、成功后 /me 下发 CSRF。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=pwd-admin-token",
    ],
)
class PasswordLoginApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-pwd-login-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_pwd_login")
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
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun login(
        client: HttpClient,
        body: String,
    ) = send(client, "POST", "/api/auth/login", body)

    private fun createUser(
        username: String,
        email: String,
        rawPassword: String = "correct-password-123",
    ): Long {
        val user =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = email,
                passwordHash = checkNotNull(passwordEncoder.encode(rawPassword)),
            )
        return assertNotNull(user.id)
    }

    @Test
    fun `用户名与邮箱登录 - 成功后 me 返回会话与 CSRF`() {
        val username = unique("pwd")
        val email = "$username@example.com"
        createUser(username, email)
        val client = newClient()

        val byUsername = login(client, """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(200, byUsername.statusCode(), byUsername.body())
        val tree = json.readTree(byUsername.body())
        assertTrue(tree.get("authenticated").asBoolean())
        assertEquals("password", tree.get("authMethod").asString())
        assertTrue(tree.get("csrfToken").asString().isNotBlank())
        val setCookie =
            byUsername
                .headers()
                .firstValue("Set-Cookie")
                .orElse("")
        assertTrue(setCookie.contains("HttpOnly"))

        val me = send(client, "GET", "/api/auth/me")
        assertEquals(200, me.statusCode(), me.body())
        val meTree = json.readTree(me.body())
        assertEquals(username, meTree.get("username").asString())
        assertEquals("session", meTree.get("authMethod").asString())
        assertTrue(meTree.get("hasPassword").asBoolean())
        assertTrue(meTree.get("csrfToken").asString().isNotBlank())
        assertNotNull(userRepository.findByUsername(username)?.lastLoginAt, "登录成功更新 last_login_at")

        val byEmail = login(newClient(), """{"email":"$email","password":"correct-password-123"}""")
        assertEquals(200, byEmail.statusCode(), byEmail.body())
    }

    @Test
    fun `错误密码 未知用户 禁用用户统一 401`() {
        val username = unique("pwd-fail")
        val userId = createUser(username, "$username@example.com")

        val wrong = login(newClient(), """{"username":"$username","password":"wrong-password"}""")
        assertEquals(401, wrong.statusCode(), wrong.body())
        assertEquals("AUTH_UNAUTHORIZED", json.readTree(wrong.body()).get("code").asString())

        val unknown = login(newClient(), """{"username":"${unique("nobody")}","password":"whatever-123"}""")
        assertEquals(401, unknown.statusCode(), unknown.body())

        users.update(userId, status = "disabled")
        val disabled = login(newClient(), """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(401, disabled.statusCode(), disabled.body())
        assertEquals("AUTH_UNAUTHORIZED", json.readTree(disabled.body()).get("code").asString())
    }

    @Test
    fun `连续 5 次失败后锁定并返回 429 与 Retry-After`() {
        val username = unique("pwd-lock")
        createUser(username, "$username@example.com")

        repeat(5) {
            val failed = login(newClient(), """{"username":"$username","password":"wrong-password"}""")
            assertEquals(401, failed.statusCode(), failed.body())
        }

        val locked = login(newClient(), """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(429, locked.statusCode(), locked.body())
        assertEquals("TOO_MANY_REQUESTS", json.readTree(locked.body()).get("code").asString())
        val retryAfter =
            locked
                .headers()
                .firstValue("Retry-After")
                .orElse("")
                .toLongOrNull() ?: 0
        assertTrue(retryAfter > 0, "应带 Retry-After")
    }
}
