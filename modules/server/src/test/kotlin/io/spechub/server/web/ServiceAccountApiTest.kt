package io.spechub.server.web

import io.spechub.server.repository.ServiceAccountEntity
import io.spechub.server.repository.ServiceAccountRepository
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.ServiceAccountService
import io.spechub.server.service.UserService
import io.spechub.server.service.UserTokenService
import io.spechub.spec.gstore.GitCli
import io.spechub.spec.index.Role
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C5：项目级服务账号（viewer/editor、跨项目隔离、轮换 / 吊销、不可登录 Web）。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=sa-admin-token",
    ],
)
class ServiceAccountApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var userTokens: UserTokenService

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var repos: RepoService

    @Autowired
    lateinit var serviceAccounts: ServiceAccountService

    @Autowired
    lateinit var serviceAccountRepository: ServiceAccountRepository

    companion object {
        private const val ADMIN = "sa-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-sa-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_service_account")
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

    private fun createProject(): Long {
        val suffix = unique("p")
        return assertNotNull(projects.create(orgs.defaultOrgId(), "Service $suffix", suffix).id)
    }

    private fun createRepo(projectId: Long): Long {
        val root = TEMPDIR.resolve("repo-${UUID.randomUUID()}")
        Files.createDirectories(root)
        val bare = root.resolve("origin.git")
        GitCli().run(root, listOf("init", "--bare", "-b", "main", bare.toAbsolutePath().toString())).require("init bare")
        return repos.register(projectId, "file://${bare.toAbsolutePath()}").id
    }

    private data class TestAccount(
        val id: Long,
        val token: String,
    )

    private fun createAccount(
        projectId: Long,
        role: String,
        name: String = unique("sa"),
        days: Long = 30,
    ): TestAccount {
        val response =
            send(
                "POST",
                "/api/service-accounts",
                """{"projectId":$projectId,"name":"$name","role":"$role","expiresInDays":$days}""",
                token = ADMIN,
            )
        assertEquals(201, response.statusCode(), response.body())
        val tree = json.readTree(response.body())
        return TestAccount(tree.at("/account/id").asLong(), tree.get("token").asString())
    }

    @Test
    fun `创建 列表 轮换 吊销 - 明文只返回一次且旧密钥立即失效`() {
        val projectId = createProject()
        val account = createAccount(projectId, "editor", name = "ci-${unique("n")}")
        assertTrue(account.token.startsWith("shs_"))

        val listed = send("GET", "/api/service-accounts?projectId=$projectId", token = ADMIN)
        assertEquals(200, listed.statusCode(), listed.body())
        assertTrue(!listed.body().contains("tokenHash"), "列表不含 hash")
        assertTrue(!listed.body().contains("secret"), "列表不含明文")
        val list = json.readTree(listed.body())
        assertEquals(1, list.size())
        assertTrue(
            list
                .get(0)
                .get("prefix")
                .asString()
                .startsWith("shs_"),
        )

        val me = send("GET", "/api/auth/me", token = account.token)
        assertEquals(200, me.statusCode(), me.body())
        assertEquals("bearer", json.readTree(me.body()).get("authMethod").asString())
        assertEquals("editor", json.readTree(me.body()).at("/projectRoles/$projectId").asString())

        val rotated = send("POST", "/api/service-accounts/${account.id}/rotate", """{"expiresInDays":60}""", token = ADMIN)
        assertEquals(200, rotated.statusCode(), rotated.body())
        val newToken = json.readTree(rotated.body()).get("token").asString()
        assertTrue(newToken != account.token)
        assertEquals(401, send("GET", "/api/auth/me", token = account.token).statusCode(), "旧密钥立即失效")
        assertEquals(200, send("GET", "/api/auth/me", token = newToken).statusCode(), "新密钥可用")

        assertEquals(204, send("DELETE", "/api/service-accounts/${account.id}", token = ADMIN).statusCode())
        assertEquals(401, send("GET", "/api/auth/me", token = newToken).statusCode(), "吊销后立即 401")
        val afterRevoke = json.readTree(send("GET", "/api/service-accounts?projectId=$projectId", token = ADMIN).body())
        assertEquals("revoked", afterRevoke.get(0).get("status").asString())
    }

    @Test
    fun `viewer 可读不可写 - editor 可提交写回评审`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val viewer = createAccount(projectId, "viewer")
        val editor = createAccount(projectId, "editor")

        assertEquals(200, send("GET", "/api/repos/$repoId/apis", token = viewer.token).statusCode())
        assertEquals(
            403,
            send(
                "PUT",
                "/api/repos/$repoId/spec",
                """{"path":"specs/pet/openapi.yaml","content":"openapi: 3.1.0\ninfo: { title: Pet, version: \"1.0.0\" }\npaths: {}\n"}""",
                token = viewer.token,
            ).statusCode(),
            "viewer 不可写",
        )
        val write =
            send(
                "PUT",
                "/api/repos/$repoId/spec",
                """{"path":"specs/pet/openapi.yaml","content":"openapi: 3.1.0\ninfo: { title: Pet, version: \"1.0.0\" }\npaths: {}\n"}""",
                token = editor.token,
            )
        assertEquals(200, write.statusCode(), write.body())
        assertEquals("pending", json.readTree(write.body()).get("status").asString())
        val reviews = json.readTree(send("GET", "/api/reviews?status=pending", token = editor.token).body())
        assertTrue(reviews.toString().contains("service:"), "服务账号 actor 记为 service:<name>：$reviews")

        // 服务账号不可管理服务账号 / 团队 / 组织用户
        assertEquals(403, send("GET", "/api/service-accounts?projectId=$projectId", token = editor.token).statusCode())
        assertEquals(403, send("GET", "/api/teams", token = editor.token).statusCode())
        assertEquals(403, send("GET", "/api/auth/users", token = editor.token).statusCode())
    }

    @Test
    fun `跨项目 404 - 列表 详情 导出 mock`() {
        val projectA = createProject()
        val projectB = createProject()
        val repoA = createRepo(projectA)
        val repoB = createRepo(projectB)
        val account = createAccount(projectA, "viewer")

        val projectsList = json.readTree(send("GET", "/api/projects", token = account.token).body())
        assertEquals(1, projectsList.size(), "服务账号只看到所属项目：$projectsList")
        assertEquals(projectA, projectsList.get(0).get("id").asLong())

        assertEquals(404, send("GET", "/api/apis?projectId=$projectB", token = account.token).statusCode())
        assertEquals(404, send("GET", "/api/repos/$repoB/apis", token = account.token).statusCode())
        assertEquals(404, send("GET", "/api/repos/$repoB/export/openapi31?stage=release", token = account.token).statusCode())
        assertEquals(404, send("GET", "/mock/$repoB/pets", token = account.token).statusCode())

        // 自身项目仍可读
        assertEquals(200, send("GET", "/api/repos/$repoA/apis", token = account.token).statusCode())
    }

    @Test
    fun `过期与吊销立即 401 - 不可登录 Web`() {
        val projectId = createProject()
        val account = createAccount(projectId, "viewer")
        val login = send("POST", "/api/auth/login", """{"token":"${account.token}"}""")
        assertEquals(401, login.statusCode(), "服务账号不可登录 Web")

        val raw = UserTokenService.generateRaw(ServiceAccountService.PREFIX)
        serviceAccountRepository.save(
            ServiceAccountEntity(
                orgId = orgs.defaultOrgId(),
                projectId = projectId,
                name = "expired-${unique("sa")}",
                tokenPrefix = UserTokenService.displayPrefix(raw),
                tokenHash = UserTokenService.sha256(raw),
                role = "viewer",
                status = "active",
                expiresAt = Instant.now().minusSeconds(1),
                createdAt = Instant.now(),
            ),
        )
        assertEquals(401, send("GET", "/api/auth/me", token = raw).statusCode(), "过期服务账号 401")
    }

    @Test
    fun `创建校验 - 重名 角色 有效期`() {
        val projectId = createProject()
        val name = "dup-${unique("n")}"
        createAccount(projectId, "viewer", name = name)
        val duplicate =
            send(
                "POST",
                "/api/service-accounts",
                """{"projectId":$projectId,"name":"${name.uppercase()}","role":"viewer"}""",
                token = ADMIN,
            )
        assertEquals(409, duplicate.statusCode(), duplicate.body())

        val badRole =
            send("POST", "/api/service-accounts", """{"projectId":$projectId,"name":"${unique("r")}","role":"owner"}""", token = ADMIN)
        assertEquals(400, badRole.statusCode(), badRole.body())

        val badDays =
            send("POST", "/api/service-accounts", """{"projectId":$projectId,"name":"${unique("d")}","role":"viewer","expiresInDays":366}""", token = ADMIN)
        assertEquals(400, badDays.statusCode(), badDays.body())
    }

    @Test
    fun `项目删除清理服务账号 - 令牌随即 401`() {
        val projectId = createProject()
        val account = createAccount(projectId, "viewer")
        projects.archive(projectId)
        projects.delete(projectId)
        assertEquals(401, send("GET", "/api/auth/me", token = account.token).statusCode())
        assertEquals(null, serviceAccounts.find(account.id))
    }

    @Test
    fun `项目 admin 跨项目访问 404`() {
        val projectA = createProject()
        val projectB = createProject()
        val username = unique("pm")
        val user =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = "$username@example.com",
                passwordHash = checkNotNull(passwordEncoder.encode("user-password-123")),
            )
        val userId = assertNotNull(user.id)
        users.setMembership(userId, projectA, Role.ADMIN)
        val userToken = userTokens.issue(userId, "pm", UserTokenService.PURPOSE_API, Instant.now().plusSeconds(3600)).raw

        assertEquals(200, send("GET", "/api/service-accounts?projectId=$projectA", token = userToken).statusCode())
        assertEquals(404, send("GET", "/api/service-accounts?projectId=$projectB", token = userToken).statusCode())
        assertEquals(
            404,
            send(
                "POST",
                "/api/service-accounts",
                """{"projectId":$projectB,"name":"${unique("x")}","role":"viewer"}""",
                token = userToken,
            ).statusCode(),
        )
    }
}
