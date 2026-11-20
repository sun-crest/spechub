package io.spechub.server.web

import io.spechub.server.repository.AuditEventRepository
import io.spechub.spec.index.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 阶段 A：多项目成员 API（添加/列表/改角色/移除、owner 锁定、项目 admin 权限）。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
class ProjectMembershipApiTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var auditEvents: AuditEventRepository

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private const val ADMIN = "test-admin-token-123"
        private val TEMPDIR: Path = createTempDirectory("spechub-projmember-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_proj_member")
        }
    }

    private data class TestUser(
        val id: Long,
        val token: String,
        val username: String,
    )

    private fun uniqueSuffix(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String = ADMIN,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $token")
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun codeOf(body: String): String? = json.readTree(body).get("code")?.asText()

    private fun longField(
        body: String,
        field: String,
    ): Long = checkNotNull(json.readTree(body).get(field)).asLong()

    private fun createOrgUser(base: String): TestUser {
        val suffix = uniqueSuffix()
        val username = "$base-$suffix"
        val resp = send("POST", "/api/auth/users", """{"username":"$username"}""")
        assertEquals(200, resp.statusCode(), resp.body())
        val node = json.readTree(resp.body())
        val userNode = checkNotNull(node.get("user"))
        return TestUser(
            id = checkNotNull(userNode.get("id")).asLong(),
            token = checkNotNull(node.get("token")).asText(),
            username = username,
        )
    }

    private fun createProject(owner: TestUser): Long {
        val slug = "m-" + uniqueSuffix()
        val resp = send("POST", "/api/projects", """{"name":"成员测试","slug":"$slug","ownerId":${owner.id}}""")
        assertEquals(201, resp.statusCode(), resp.body())
        return longField(resp.body(), "id")
    }

    @Test
    fun `多项目成员 - 添加、列表、改角色、移除 与 owner 锁定`() {
        val owner = createOrgUser("owner")
        val member = createOrgUser("member")
        val projectAdmin = createOrgUser("padmin")
        val extra = createOrgUser("extra")
        val stranger = createOrgUser("stranger")
        val projectId = createProject(owner)

        val initial = send("GET", "/api/projects/$projectId/members", token = owner.token)
        assertEquals(200, initial.statusCode(), initial.body())
        assertEquals(1, json.readTree(initial.body()).size(), "初始只有 owner")

        val added =
            send(
                "POST",
                "/api/projects/$projectId/members",
                """{"username":"${member.username}","role":"${Role.VIEWER}"}""",
                owner.token,
            )
        assertEquals(201, added.statusCode(), added.body())
        assertEquals(Role.VIEWER, checkNotNull(json.readTree(added.body()).get("role")).asText())

        val promoted = send("PATCH", "/api/projects/$projectId/members/${member.id}", """{"role":"${Role.EDITOR}"}""", owner.token)
        assertEquals(200, promoted.statusCode(), promoted.body())
        assertEquals(Role.EDITOR, checkNotNull(json.readTree(promoted.body()).get("role")).asText())

        assertEquals(204, send("DELETE", "/api/projects/$projectId/members/${member.id}", token = owner.token).statusCode())
        val afterRemove = send("GET", "/api/projects/$projectId/members", token = owner.token)
        assertEquals(1, json.readTree(afterRemove.body()).size(), "移除后只剩 owner")

        // 项目 admin 也能管理成员
        val addAdmin =
            send(
                "POST",
                "/api/projects/$projectId/members",
                """{"username":"${projectAdmin.username}","role":"${Role.ADMIN}"}""",
                owner.token,
            )
        assertEquals(201, addAdmin.statusCode(), addAdmin.body())
        val addByAdmin =
            send(
                "POST",
                "/api/projects/$projectId/members",
                """{"username":"${extra.username}","role":"${Role.VIEWER}"}""",
                projectAdmin.token,
            )
        assertEquals(201, addByAdmin.statusCode(), addByAdmin.body())

        // owner 角色不能被成员接口修改/移除
        val ownerRole = send("PATCH", "/api/projects/$projectId/members/${owner.id}", """{"role":"${Role.ADMIN}"}""", projectAdmin.token)
        assertEquals(409, ownerRole.statusCode(), ownerRole.body())
        assertEquals("CONFLICT", codeOf(ownerRole.body()))
        val ownerRemove = send("DELETE", "/api/projects/$projectId/members/${owner.id}", token = projectAdmin.token)
        assertEquals(409, ownerRemove.statusCode(), ownerRemove.body())

        // 非成员看不到成员列表
        assertEquals(404, send("GET", "/api/projects/$projectId/members", token = stranger.token).statusCode())
    }

    @Test
    fun `项目成员动作写入审计`() {
        val owner = createOrgUser("audit-owner")
        val member = createOrgUser("audit-member")
        val projectId = createProject(owner)

        assertEquals(
            201,
            send(
                "POST",
                "/api/projects/$projectId/members",
                """{"username":"${member.username}","role":"${Role.VIEWER}"}""",
                owner.token,
            ).statusCode(),
        )
        assertEquals(
            200,
            send("PATCH", "/api/projects/$projectId/members/${member.id}", """{"role":"${Role.EDITOR}"}""", owner.token).statusCode(),
        )
        assertEquals(204, send("DELETE", "/api/projects/$projectId/members/${member.id}", token = owner.token).statusCode())

        val actions = auditEvents.findAll().map { it.action }.toSet()
        listOf("project.member.add", "project.member.role", "project.member.delete").forEach {
            assertTrue(it in actions, "缺少审计动作 $it：$actions")
        }
    }
}
