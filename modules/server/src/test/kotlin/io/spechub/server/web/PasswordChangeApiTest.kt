package io.spechub.server.web

import io.spechub.server.repository.UserStatus
import io.spechub.server.service.UserService
import io.spechub.server.service.UserTokenService
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C3：改密（当前密码 / 会话吊销）、邀请 / 重置令牌设置密码、密码策略。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=change-admin-token",
    ],
)
class PasswordChangeApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var tokens: UserTokenService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-pwd-change-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_password_change")
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
        csrf: String? = null,
        token: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        if (csrf != null) builder.header("X-CSRF-Token", csrf)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun createUser(
        username: String,
        rawPassword: String = "correct-password-123",
        status: String = UserStatus.ACTIVE,
    ): Long {
        val user =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = "$username@example.com",
                status = status,
                passwordHash = if (status == UserStatus.INVITED) null else checkNotNull(passwordEncoder.encode(rawPassword)),
            )
        return assertNotNull(user.id)
    }

    private fun login(
        client: HttpClient,
        username: String,
        rawPassword: String,
    ): String {
        val response = send(client, "POST", "/api/auth/login", """{"username":"$username","password":"$rawPassword"}""")
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body()).get("csrfToken").asString()
    }

    @Test
    fun `改密 - 旧密码失效 当前会话保留 其它会话吊销`() {
        val username = unique("change")
        createUser(username)
        val clientA = newClient()
        val clientB = newClient()
        val csrfA = login(clientA, username, "correct-password-123")
        login(clientB, username, "correct-password-123")

        assertEquals(200, send(clientB, "GET", "/api/auth/me").statusCode())

        val changed =
            send(
                clientA,
                "POST",
                "/api/auth/password",
                """{"currentPassword":"correct-password-123","newPassword":"new-password-456"}""",
                csrf = csrfA,
            )
        assertEquals(204, changed.statusCode(), changed.body())

        assertEquals(200, send(clientA, "GET", "/api/auth/me").statusCode(), "当前会话保留")
        assertEquals(401, send(clientB, "GET", "/api/auth/me").statusCode(), "其它会话吊销")

        val oldLogin = send(newClient(), "POST", "/api/auth/login", """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(401, oldLogin.statusCode(), "旧密码失效")
        login(newClient(), username, "new-password-456")
    }

    @Test
    fun `改密校验 - 当前密码与密码策略`() {
        val username = unique("policy")
        createUser(username)
        val client = newClient()
        val csrf = login(client, username, "correct-password-123")

        val missingCurrent =
            send(client, "POST", "/api/auth/password", """{"newPassword":"another-password-123"}""", csrf = csrf)
        assertEquals(400, missingCurrent.statusCode(), missingCurrent.body())
        assertEquals("VALIDATION", json.readTree(missingCurrent.body()).get("code").asString())

        val wrongCurrent =
            send(
                client,
                "POST",
                "/api/auth/password",
                """{"currentPassword":"wrong-password","newPassword":"another-password-123"}""",
                csrf = csrf,
            )
        assertEquals(401, wrongCurrent.statusCode(), wrongCurrent.body())

        val short =
            send(
                client,
                "POST",
                "/api/auth/password",
                """{"currentPassword":"correct-password-123","newPassword":"short"}""",
                csrf = csrf,
            )
        assertEquals(400, short.statusCode(), short.body())
        assertEquals(
            "newPassword",
            json
                .readTree(short.body())
                .get("fieldErrors")
                .get(0)
                .get("field")
                .asString(),
        )

        val sameAsUsername =
            send(
                client,
                "POST",
                "/api/auth/password",
                """{"currentPassword":"correct-password-123","newPassword":"$username"}""",
                csrf = csrf,
            )
        assertEquals(400, sameAsUsername.statusCode(), sameAsUsername.body())
    }

    @Test
    fun `邀请令牌设置密码 - 单次使用且不自动登录`() {
        val username = unique("invite")
        val userId = createUser(username, status = UserStatus.INVITED)
        val invite = tokens.issue(userId, "invite", UserTokenService.PURPOSE_INVITE, Instant.now().plus(Duration.ofDays(7)))

        val setup =
            send(
                newClient(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"${invite.raw}","newPassword":"setup-password-123"}""",
            )
        assertEquals(200, setup.statusCode(), setup.body())

        val user = assertNotNull(users.findById(userId))
        assertEquals(UserStatus.ACTIVE, user.status)
        assertTrue(user.hasPassword)

        login(newClient(), username, "setup-password-123")

        val reused =
            send(
                newClient(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"${invite.raw}","newPassword":"another-password-123"}""",
            )
        assertEquals(400, reused.statusCode(), "邀请令牌只能使用一次")
    }

    @Test
    fun `重置令牌设置密码 - 清 mustChangePassword 且旧密码失效`() {
        val username = unique("reset")
        val userId = createUser(username)
        users.update(userId, mustChangePassword = true)
        val reset = tokens.issue(userId, "reset", UserTokenService.PURPOSE_PASSWORD_RESET, Instant.now().plus(Duration.ofHours(24)))

        val setup =
            send(
                newClient(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"${reset.raw}","newPassword":"reset-password-123"}""",
            )
        assertEquals(200, setup.statusCode(), setup.body())

        val user = assertNotNull(users.findById(userId))
        assertFalse(user.mustChangePassword, "设置密码后清除 mustChangePassword")
        login(newClient(), username, "reset-password-123")
        val old =
            send(newClient(), "POST", "/api/auth/login", """{"username":"$username","password":"correct-password-123"}""")
        assertEquals(401, old.statusCode(), "旧密码失效")
    }

    @Test
    fun `无效设置令牌返回 400`() {
        val setup =
            send(
                newClient(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"invalid-setup-token","newPassword":"setup-password-123"}""",
            )
        assertEquals(400, setup.statusCode(), setup.body())
    }
}
