package io.spechub.server.web

import io.spechub.server.service.OrgService
import io.spechub.server.service.UserLifecycleService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C4：禁用 / 启用 / 重置 / 删除、最后 org_admin 保护、邮箱冲突、邀请令牌。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=lifecycle-admin-token",
    ],
)
class UserLifecycleApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var lifecycle: UserLifecycleService

    @Autowired
    lateinit var orgs: OrgService

    companion object {
        private const val ADMIN = "lifecycle-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-lifecycle-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_lifecycle")
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
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (csrf != null) builder.header("X-CSRF-Token", csrf)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private data class CreatedUser(
        val id: Long,
        val username: String,
        val token: String,
        val setupToken: String?,
    )

    private fun createUser(
        withPassword: Boolean,
        orgRole: String = "org_member",
        email: String? = null,
    ): CreatedUser {
        val username = unique("u")
        val body =
            buildString {
                append("{\"username\":\"$username\",\"displayName\":\"Lifecycle\",\"orgRole\":\"$orgRole\"")
                if (email != null) append(",\"email\":\"$email\"")
                if (withPassword) append(",\"password\":\"user-password-123\"")
                append("}")
            }
        val response = send(client(), "POST", "/api/auth/users", body, token = ADMIN)
        assertEquals(200, response.statusCode(), response.body())
        val tree = json.readTree(response.body())
        return CreatedUser(
            id = tree.at("/user/id").asLong(),
            username = username,
            token = tree.at("/token").asString(),
            setupToken = tree.get("setupToken")?.asString()?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun login(
        user: CreatedUser,
        password: String = "user-password-123",
    ): Pair<HttpClient, String> {
        val client = client()
        val response = send(client, "POST", "/api/auth/login", """{"username":"${user.username}","password":"$password"}""")
        assertEquals(200, response.statusCode(), response.body())
        return client to json.readTree(response.body()).get("csrfToken").asString()
    }

    @Test
    fun `禁用用户 - 会话与令牌立即失效 启用不恢复旧凭据`() {
        val user = createUser(withPassword = true)
        val (session, _) = login(user)
        assertEquals(200, send(session, "GET", "/api/auth/me").statusCode())
        assertEquals(200, send(client(), "GET", "/api/auth/me", token = user.token).statusCode())

        val disabled = send(client(), "POST", "/api/auth/users/${user.id}/disable", token = ADMIN)
        assertEquals(200, disabled.statusCode(), disabled.body())
        assertEquals("disabled", json.readTree(disabled.body()).get("status").asString())

        assertEquals(401, send(session, "GET", "/api/auth/me").statusCode(), "禁用后会话立即 401")
        assertEquals(401, send(client(), "GET", "/api/auth/me", token = user.token).statusCode(), "禁用后令牌立即 401")

        assertEquals(200, send(client(), "POST", "/api/auth/users/${user.id}/enable", token = ADMIN).statusCode())
        assertEquals(401, send(session, "GET", "/api/auth/me").statusCode(), "启用不恢复旧会话")
        assertEquals(401, send(client(), "GET", "/api/auth/me", token = user.token).statusCode(), "启用不恢复已吊销令牌")

        login(user)
    }

    @Test
    fun `重置密码 - 一次性令牌且旧会话与令牌失效`() {
        val user = createUser(withPassword = true)
        val (session, _) = login(user)

        val reset = send(client(), "POST", "/api/auth/users/${user.id}/password/reset", token = ADMIN)
        assertEquals(200, reset.statusCode(), reset.body())
        val setupToken = json.readTree(reset.body()).get("setupToken").asString()
        assertTrue(setupToken.isNotBlank())
        assertEquals(401, send(session, "GET", "/api/auth/me").statusCode(), "重置密码吊销会话")
        assertEquals(401, send(client(), "GET", "/api/auth/me", token = user.token).statusCode(), "重置密码吊销令牌")

        val setup =
            send(
                client(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"$setupToken","newPassword":"reset-password-123"}""",
            )
        assertEquals(200, setup.statusCode(), setup.body())
        login(user, "reset-password-123")
        assertEquals(
            401,
            send(client(), "POST", "/api/auth/login", """{"username":"${user.username}","password":"user-password-123"}""").statusCode(),
            "旧密码失效",
        )
        assertEquals(
            400,
            send(
                client(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"$setupToken","newPassword":"another-password-123"}""",
            ).statusCode(),
            "重置令牌只能使用一次",
        )
    }

    @Test
    fun `最后一名可登录 org_admin 保护 - 自禁用与降级 409`() {
        val orgId = orgs.defaultOrgId()
        val admin = assertNotNull(users.findByUsername("admin"))
        // bootstrap admin 是当前唯一 org_admin
        assertFailsWith<ApiException> {
            lifecycle.ensureNotLastLoginnableOrgAdmin(assertNotNull(admin.id), orgId)
        }

        val second = createUser(withPassword = true, orgRole = "org_admin")
        lifecycle.ensureNotLastLoginnableOrgAdmin(assertNotNull(admin.id), orgId)

        // 禁用第二名 admin 后，bootstrap admin 再次成为最后一名
        assertEquals(200, send(client(), "POST", "/api/auth/users/${second.id}/disable", token = ADMIN).statusCode())
        assertFailsWith<ApiException> {
            lifecycle.ensureNotLastLoginnableOrgAdmin(assertNotNull(admin.id), orgId)
        }

        // 自禁用 / 自重置 / 自删除 → 400
        assertEquals(400, send(client(), "POST", "/api/auth/users/${admin.id}/disable", token = ADMIN).statusCode())
        assertEquals(400, send(client(), "POST", "/api/auth/users/${admin.id}/password/reset", token = ADMIN).statusCode())
        assertEquals(400, send(client(), "DELETE", "/api/auth/users/${admin.id}", token = ADMIN).statusCode())
    }

    @Test
    fun `创建与更新邮箱唯一 - 邀请用户返回 setupToken`() {
        val email = "${unique("mail")}@example.com"
        val first = createUser(withPassword = true, email = email)
        assertNull(first.setupToken)

        val duplicate = send(client(), "POST", "/api/auth/users", """{"username":"${unique("dup")}","email":"${email.uppercase()}"}""", token = ADMIN)
        assertEquals(409, duplicate.statusCode(), duplicate.body())

        val second = createUser(withPassword = true)
        val conflict =
            send(client(), "PATCH", "/api/auth/users/${second.id}", """{"email":"${email.uppercase()}"}""", token = ADMIN)
        assertEquals(409, conflict.statusCode(), conflict.body())

        val newEmail = "${unique("new")}@example.com"
        val updated =
            send(client(), "PATCH", "/api/auth/users/${second.id}", """{"displayName":"Updated","email":"$newEmail"}""", token = ADMIN)
        assertEquals(200, updated.statusCode(), updated.body())
        assertEquals("Updated", json.readTree(updated.body()).get("displayName").asString())
        assertEquals(newEmail, json.readTree(updated.body()).get("email").asString())

        val invited = createUser(withPassword = false, email = "${unique("invite")}@example.com")
        val listed = json.readTree(send(client(), "GET", "/api/auth/users", token = ADMIN).body())
        val invitedStatus = listed.firstOrNull { it.get("id")?.asLong() == invited.id }?.get("status")?.asString()
        assertEquals("invited", invitedStatus)
        assertNotNull(invited.setupToken, "邀请用户返回一次性 setupToken")
        val setup =
            send(
                client(),
                "POST",
                "/api/auth/password/setup",
                """{"token":"${invited.setupToken}","newPassword":"invited-password-123"}""",
            )
        assertEquals(200, setup.statusCode(), setup.body())
        login(invited, "invited-password-123")
    }

    @Test
    fun `删除用户 - 会话与令牌失效 用户目录移除`() {
        val user = createUser(withPassword = true)
        val (session, _) = login(user)

        assertEquals(204, send(client(), "DELETE", "/api/auth/users/${user.id}", token = ADMIN).statusCode())
        assertEquals(401, send(session, "GET", "/api/auth/me").statusCode(), "删除后会话失效")
        assertEquals(401, send(client(), "GET", "/api/auth/me", token = user.token).statusCode(), "删除后令牌失效")
        assertNull(users.findById(user.id))
    }
}
