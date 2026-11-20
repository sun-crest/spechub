package io.spechub.server.web

import io.spechub.server.repository.AuditEventEntity
import io.spechub.server.repository.AuditEventRepository
import io.spechub.server.service.AuditService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.UserService
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
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C7：管理动作审计写入 / 查询 / 权限 / 过滤 / 敏感值。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=audit-admin-token",
    ],
)
class AuditApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var auditEvents: AuditEventRepository

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var users: UserService

    companion object {
        private const val ADMIN = "audit-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-audit-" + System.nanoTime())
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
            TestManagementDb.register(registry, "t_audit")
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

    private fun auditList(query: String = ""): List<Map<String, Any?>> {
        val response = send(client(), "GET", "/api/audit$query", token = ADMIN)
        assertEquals(200, response.statusCode(), response.body())
        val root = json.readTree(response.body())
        return (0 until root.size()).map { i ->
            val node = root.get(i)
            mapOf<String, Any?>(
                "action" to node.get("action").asString(),
                "actorName" to node.get("actorName").asString(),
                "actorType" to node.get("actorType").asString(),
                "outcome" to node.get("outcome").asString(),
                "detail" to node.get("detail")?.asString(),
                "targetId" to node.get("targetId")?.asString(),
                "createdAt" to node.get("createdAt")?.asString(),
            )
        }
    }

    @Test
    fun `管理动作写入审计 - org_admin 可查且过滤可用`() {
        val projectName = unique("audit-p")
        val project = assertNotNull(json.readTree(send(client(), "POST", "/api/projects", """{"name":"$projectName","slug":"$projectName"}""", token = ADMIN).body()).get("id")?.asLong())
        val projectId = project

        val username = unique("audit-u")
        val created = send(client(), "POST", "/api/auth/users", """{"username":"$username","password":"user-password-123"}""", token = ADMIN)
        assertEquals(200, created.statusCode(), created.body())
        val userId = json.readTree(created.body()).at("/user/id").asLong()
        val userToken = json.readTree(created.body()).get("token").asString()

        assertEquals(201, send(client(), "POST", "/api/auth/tokens", """{"name":"audit-token"}""", token = userToken).statusCode())
        assertEquals(200, send(client(), "POST", "/api/auth/users/$userId/disable", token = ADMIN).statusCode())
        val account =
            send(client(), "POST", "/api/service-accounts", """{"projectId":$projectId,"name":"${unique("sa")}","role":"viewer"}""", token = ADMIN)
        assertEquals(201, account.statusCode(), account.body())
        val accountId = json.readTree(account.body()).at("/account/id").asLong()
        assertEquals(204, send(client(), "DELETE", "/api/service-accounts/$accountId", token = ADMIN).statusCode())

        // 登录成功 / 失败
        val sessionClient = client()
        assertEquals(200, send(sessionClient, "POST", "/api/auth/login", """{"token":"$ADMIN"}""").statusCode())
        val failurePassword = unique("wrong")
        val failure =
            send(
                client(),
                "POST",
                "/api/auth/login",
                """{"username":"${unique("nobody")}","password":"$failurePassword"}""",
            )
        assertEquals(401, failure.statusCode(), failure.body())
        assertEquals(204, send(sessionClient, "POST", "/api/auth/logout").statusCode())

        val list = auditList()
        val actions = list.map { it["action"] }.toSet()
        assertTrue("project.create" in actions, "actions=$actions")
        assertTrue("user.create" in actions)
        assertTrue("user.disable" in actions)
        assertTrue("token.create" in actions)
        assertTrue("service_account.create" in actions)
        assertTrue("service_account.revoke" in actions)
        assertTrue("auth.login.success" in actions)
        assertTrue("auth.login.failure" in actions)
        assertTrue("auth.logout" in actions)

        // 过滤
        val disabled = auditList("?action=user.disable")
        assertTrue(disabled.isNotEmpty() && disabled.all { it["action"] == "user.disable" })
        assertEquals("user", disabled.first()["actorType"])

        val failures = auditList("?outcome=failure")
        assertTrue(failures.isNotEmpty() && failures.all { it["outcome"] == "failure" })
        assertTrue(failures.none { (it["detail"] as? String)?.contains(failurePassword) == true }, "detail 不得包含密码")
        assertTrue(failures.none { (it["detail"] as? String)?.contains("shu_") == true }, "detail 不得包含令牌")

        val byActor = auditList("?actor=admin")
        assertTrue(byActor.isNotEmpty() && byActor.all { it["actorName"] == "admin" })

        val now = Instant.now()
        val ranged = auditList("?from=${now.minusSeconds(3600)}&to=${now.plusSeconds(60)}")
        assertTrue(ranged.isNotEmpty())
        assertEquals(
            400,
            send(client(), "GET", "/api/audit?from=not-an-instant", token = ADMIN).statusCode(),
            "非法 from 返回 400",
        )
        assertEquals(200, send(client(), "GET", "/api/audit?limit=500", token = ADMIN).statusCode(), "limit 最大 200")
    }

    @Test
    fun `项目 admin 与服务账号不可查询 - 跨 org 不可见`() {
        val projectName = unique("audit-perm")
        val project = assertNotNull(projects.create(orgs.defaultOrgId(), projectName, projectName).id)
        val username = unique("audit-pm")
        val pm =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = "$username@example.com",
                passwordHash = null,
            )
        val pmId = assertNotNull(pm.id)
        users.setMembership(pmId, project, "admin")
        // 通过 API 由 admin 代为签发用户令牌：
        val created = send(client(), "POST", "/api/auth/users/$pmId/token", token = ADMIN)
        assertEquals(200, created.statusCode(), created.body())
        val projectAdminToken = json.readTree(created.body()).get("token").asString()
        assertTrue(projectAdminToken.startsWith("shu_"))
        assertEquals(403, send(client(), "GET", "/api/audit", token = projectAdminToken).statusCode(), "项目 admin 403")

        val account =
            send(client(), "POST", "/api/service-accounts", """{"projectId":$project,"name":"${unique("sa")}","role":"viewer"}""", token = ADMIN)
        assertEquals(201, account.statusCode(), account.body())
        val serviceToken = json.readTree(account.body()).get("token").asString()
        assertEquals(403, send(client(), "GET", "/api/audit", token = serviceToken).statusCode(), "服务账号 403")

        // 跨 org 事件不可见
        val otherOrgId = orgs.defaultOrgId() + 999
        auditEvents.save(
            AuditEventEntity(
                orgId = otherOrgId,
                actorName = "other-org",
                actorType = "user",
                action = "project.create",
                outcome = "success",
                createdAt = Instant.now(),
            ),
        )
        val list = auditList("?action=project.create")
        assertTrue(list.none { it["actorName"] == "other-org" }, "跨 org 不可见：$list")
    }
}
