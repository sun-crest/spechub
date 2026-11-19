package io.spechub.server.web

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C4：用户 API 令牌 CRUD（自己的 / org_admin 运维）、过期状态、明文只返回一次。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=token-admin-token",
    ],
)
class UserTokenApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var tokens: UserTokenService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    companion object {
        private const val ADMIN = "token-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-user-token-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_user_token_api")
        }
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private data class TestUser(
        val id: Long,
        val token: String,
    )

    private fun createUser(): TestUser {
        val username = unique("tok")
        val user =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = "$username@example.com",
                passwordHash = checkNotNull(passwordEncoder.encode("user-password-123")),
            )
        val userId = assertNotNull(user.id)
        val issued = tokens.issue(userId, "initial", UserTokenService.PURPOSE_API, Instant.now().plus(Duration.ofDays(90)))
        return TestUser(userId, issued.raw)
    }

    @Test
    fun `创建与列表 - 列表不含明文与 hash 且明文只返回一次`() {
        val user = createUser()

        val listed = send("GET", "/api/auth/tokens", token = user.token)
        assertEquals(200, listed.statusCode(), listed.body())
        val listJson = listed.body()
        assertTrue(!listJson.contains("secret"), "列表不得返回明文：$listJson")
        assertTrue(!listJson.contains("tokenHash"), "列表不得返回 hash：$listJson")
        val list = json.readTree(listJson)
        assertTrue(list.size() >= 1)
        val initial = list.first { it.get("purpose").asString() == "api" }
        assertTrue(initial.get("prefix").asString().startsWith("shu_"))
        assertEquals("active", initial.get("status").asString())

        val created = send("POST", "/api/auth/tokens", """{"name":"ci","expiresInDays":30}""", token = user.token)
        assertEquals(201, created.statusCode(), created.body())
        val createdTree = json.readTree(created.body())
        val secret = createdTree.get("secret").asString()
        assertTrue(secret.startsWith("shu_"))
        assertEquals("active", createdTree.at("/token/status").asString())
        assertEquals(200, send("GET", "/api/auth/me", token = secret).statusCode(), "新令牌立即可用")

        assertEquals(204, send("DELETE", "/api/auth/tokens/${createdTree.at("/token/id").asLong()}", token = user.token).statusCode())
        assertEquals(401, send("GET", "/api/auth/me", token = secret).statusCode(), "吊销后立即 401")

        val invalidDays = send("POST", "/api/auth/tokens", """{"name":"bad","expiresInDays":0}""", token = user.token)
        assertEquals(400, invalidDays.statusCode(), invalidDays.body())
    }

    @Test
    fun `他人令牌不可吊销 - 过期令牌 401 且状态 expired`() {
        val userA = createUser()
        val userB = createUser()
        val tokenB = tokens.list(userB.id).single()
        assertEquals(404, send("DELETE", "/api/auth/tokens/${tokenB.id}", token = userA.token).statusCode(), "他人令牌 404")

        val expired = tokens.issue(userA.id, "expired", UserTokenService.PURPOSE_API, Instant.now().minusSeconds(1))
        assertEquals(401, send("GET", "/api/auth/me", token = expired.raw).statusCode(), "过期令牌 401")
        val list = json.readTree(send("GET", "/api/auth/tokens", token = userA.token).body())
        val expiredView = list.first { it.get("id").asLong() == expired.entity.id }
        assertEquals("expired", expiredView.get("status").asString())
    }

    @Test
    fun `org_admin 列表与吊销指定用户令牌`() {
        val user = createUser()
        val listed = send("GET", "/api/auth/users/${user.id}/tokens", token = ADMIN)
        assertEquals(200, listed.statusCode(), listed.body())
        assertTrue(!listed.body().contains("secret"))
        val tokenId =
            json
                .readTree(listed.body())
                .first { it.get("purpose").asString() == "api" }
                .get("id")
                .asLong()

        assertEquals(204, send("DELETE", "/api/auth/users/${user.id}/tokens/$tokenId", token = ADMIN).statusCode())
        assertEquals(401, send("GET", "/api/auth/me", token = user.token).statusCode(), "admin 吊销后令牌 401")
    }

    @Test
    fun `兼容重置令牌 - 吊销旧 legacy api 并签发新 api`() {
        val user = createUser()
        val reset = send("POST", "/api/auth/users/${user.id}/token", token = ADMIN)
        assertEquals(200, reset.statusCode(), reset.body())
        val newToken = json.readTree(reset.body()).get("token").asString()
        assertTrue(newToken.startsWith("shu_"))
        assertEquals(401, send("GET", "/api/auth/me", token = user.token).statusCode(), "旧令牌失效")
        assertEquals(200, send("GET", "/api/auth/me", token = newToken).statusCode(), "新令牌可用")
    }

    @Test
    fun `admin-token 为 legacy 令牌 - V7 后仍可认证`() {
        assertEquals(200, send("GET", "/api/auth/me", token = ADMIN).statusCode())
        val list = json.readTree(send("GET", "/api/auth/tokens", token = ADMIN).body())
        assertTrue(
            (0 until list.size()).mapNotNull { list.get(it) }.any { it.get("purpose").asString() == "legacy" },
            "bootstrap admin-token 迁移为 legacy 令牌：$list",
        )
    }
}
