package io.spechub.server.web

import io.spechub.server.service.RepoService
import io.spechub.server.service.UserService
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 A：项目 CRUD / 权限 / 归档删除 / owner 转移。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
class ProjectApiTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var repos: RepoService

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private const val ADMIN = "test-admin-token-123"
        private val TEMPDIR: Path = createTempDirectory("spechub-projectapi-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_project_api")
        }
    }

    private data class TestUser(
        val id: Long,
        val token: String,
    )

    private fun uniqueSuffix(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

    private fun createUser(): TestUser {
        val suffix = uniqueSuffix()
        val rawToken = "proj-user-$suffix"
        val user = users.createUserWithMembershipAndToken("proj-$suffix", "", rawToken, null, Role.VIEWER)
        return TestUser(assertNotNull(user.id), rawToken)
    }

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

    private fun textField(
        body: String,
        field: String,
    ): String = checkNotNull(json.readTree(body).get(field)).asText()

    private fun idsOf(
        body: String,
        field: String,
    ): List<Long> {
        val node = json.readTree(body)
        return (0 until node.size()).map { checkNotNull(node.get(it).get(field)).asLong() }
    }

    private fun createProject(
        ownerId: Long? = null,
        name: String = "项目",
    ): Pair<Long, String> {
        val slug = "p-" + uniqueSuffix()
        val ownerJson = ownerId?.toString() ?: "null"
        val resp = send("POST", "/api/projects", """{"name":"$name","slug":"$slug","ownerId":$ownerJson}""")
        assertEquals(201, resp.statusCode(), resp.body())
        return longField(resp.body(), "id") to slug
    }

    @Test
    fun `创建项目 - 列表可见 且 slug 冲突返回 409`() {
        val owner = createUser()
        val (id, slug) = createProject(owner.id, "创建测试")
        val list = send("GET", "/api/projects", token = owner.token)
        assertEquals(200, list.statusCode(), list.body())
        val ids = idsOf(list.body(), "id")
        assertTrue(ids.contains(id), "owner 应能看到自己项目")

        val detail = send("GET", "/api/projects/$id", token = owner.token)
        assertEquals(200, detail.statusCode(), detail.body())
        assertEquals(slug, textField(detail.body(), "slug"))

        val dup = send("POST", "/api/projects", """{"name":"重复","slug":"$slug"}""")
        assertEquals(409, dup.statusCode(), dup.body())
        assertEquals("CONFLICT", codeOf(dup.body()))
    }

    @Test
    fun `权限 - owner 可改 editor 不可改 admin 可改可见性 普通用户不可创建`() {
        val owner = createUser()
        val (id, _) = createProject(owner.id)
        val editor = createUser()
        val projectAdmin = createUser()
        val stranger = createUser()
        users.setMembership(editor.id, id, Role.EDITOR)
        users.setMembership(projectAdmin.id, id, Role.ADMIN)

        val ownerPatch = send("PATCH", "/api/projects/$id", """{"name":"改名"}""", owner.token)
        assertEquals(200, ownerPatch.statusCode(), ownerPatch.body())

        val editorPatch = send("PATCH", "/api/projects/$id", """{"name":"editor 改名"}""", editor.token)
        assertEquals(403, editorPatch.statusCode(), editorPatch.body())

        val adminVisibility = send("PATCH", "/api/projects/$id", """{"visibility":"internal"}""", projectAdmin.token)
        assertEquals(200, adminVisibility.statusCode(), adminVisibility.body())
        assertEquals("internal", textField(adminVisibility.body(), "visibility"))

        val strangerCreate = send("POST", "/api/projects", """{"name":"越权创建"}""", stranger.token)
        assertEquals(403, strangerCreate.statusCode(), strangerCreate.body())
    }

    @Test
    fun `转移 owner - 新 owner 转正 旧 owner 降为 admin 后可归档删除`() {
        val ownerA = createUser()
        val (id, _) = createProject(ownerA.id)
        val ownerB = createUser()
        users.setMembership(ownerB.id, id, Role.EDITOR)

        val transfer = send("POST", "/api/projects/$id/transfer", """{"ownerId":${ownerB.id}}""", ownerA.token)
        assertEquals(200, transfer.statusCode(), transfer.body())
        assertEquals(ownerB.id, longField(transfer.body(), "ownerId"))
        assertEquals(Role.ADMIN, users.membershipRole(ownerA.id, id), "旧 owner 应降为 admin")
        assertEquals(Role.OWNER, users.membershipRole(ownerB.id, id), "新 owner 应为 owner")

        assertEquals(200, send("POST", "/api/projects/$id/archive", token = ownerB.token).statusCode())
        assertEquals(204, send("DELETE", "/api/projects/$id", token = ownerB.token).statusCode())
        assertEquals(404, send("GET", "/api/projects/$id", token = ownerB.token).statusCode())
    }

    @Test
    fun `删除约束 - 未归档或有仓库均返回 409`() {
        val owner = createUser()
        val (id, _) = createProject(owner.id)

        val notArchived = send("DELETE", "/api/projects/$id", token = owner.token)
        assertEquals(409, notArchived.statusCode(), notArchived.body())
        assertEquals("CONFLICT", codeOf(notArchived.body()))

        repos.register(id, "file:///project-delete-${uniqueSuffix()}", "main")
        assertEquals(200, send("POST", "/api/projects/$id/archive", token = owner.token).statusCode())
        val hasRepo = send("DELETE", "/api/projects/$id", token = owner.token)
        assertEquals(409, hasRepo.statusCode(), hasRepo.body())
        assertEquals("CONFLICT", codeOf(hasRepo.body()))
        assertTrue(send("GET", "/api/projects/$id", token = owner.token).statusCode() == 200)
    }
}
