package io.spechub.server.web

import io.spechub.server.repository.OrgEntity
import io.spechub.server.repository.OrgRepository
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.SubscriptionService
import io.spechub.server.service.TeamService
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
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 B（B3）：团队 API 权限、成员、冲突与跨组织隔离。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-team-admin-token",
    ],
)
class TeamApiTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var orgRepos: OrgRepository

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var repos: RepoService

    @Autowired
    lateinit var subscriptions: SubscriptionService

    @Autowired
    lateinit var teamService: TeamService

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private const val ADMIN = "test-team-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-teamapi-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_team_api")
        }
    }

    private data class TestUser(
        val id: Long,
        val token: String,
    )

    private data class TestTeam(
        val id: Long,
        val name: String,
    )

    private fun uniqueSuffix(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(10)

    private fun uniqueName(prefix: String): String = "$prefix-${uniqueSuffix()}"

    private fun createUser(): TestUser {
        val suffix = uniqueSuffix()
        val rawToken = "team-user-$suffix"
        val user = users.createUserWithMembershipAndToken("team-$suffix", "", rawToken, null, Role.VIEWER)
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

    private fun boolField(
        body: String,
        field: String,
    ): Boolean = checkNotNull(json.readTree(body).get(field)).asBoolean()

    private fun createTeam(): TestTeam {
        val name = uniqueName("team")
        val resp = send("POST", "/api/teams", """{"name":"$name","description":"测试团队"}""")
        assertEquals(201, resp.statusCode(), resp.body())
        return TestTeam(longField(resp.body(), "id"), textField(resp.body(), "name"))
    }

    private fun memberNode(
        listBody: String,
        teamId: Long,
    ) = json.readTree(listBody).first { it.get("id").asLong() == teamId }

    @Test
    fun `创建团队 - org_admin 创建且创建者成为 lead，普通成员 403`() {
        val name = uniqueName("team")
        val resp = send("POST", "/api/teams", """{"name":"$name","description":"测试"}""")
        assertEquals(201, resp.statusCode(), resp.body())
        val teamId = longField(resp.body(), "id")
        assertEquals(name, textField(resp.body(), "name"))
        assertEquals(TeamService.ROLE_LEAD, textField(resp.body(), "myRole"))
        assertTrue(boolField(resp.body(), "canManage"))

        val adminId = assertNotNull(users.findByUsername("admin")?.id)
        val members = send("GET", "/api/teams/$teamId/members")
        assertEquals(200, members.statusCode(), members.body())
        assertTrue(
            json.readTree(members.body()).any {
                it.get("userId").asLong() == adminId && it.get("role").asText() == TeamService.ROLE_LEAD
            },
            "创建者应成为 lead：${members.body()}",
        )

        val user = createUser()
        val denied = send("POST", "/api/teams", """{"name":"${uniqueName("denied")}"}""", user.token)
        assertEquals(403, denied.statusCode(), denied.body())
    }

    @Test
    fun `团队列表与 me 团队 - 普通成员只看自己团队角色`() {
        val team = createTeam()
        val user = createUser()
        val added = send("POST", "/api/teams/${team.id}/members", """{"userId":${user.id},"role":"member"}""")
        assertEquals(201, added.statusCode(), added.body())

        val list = send("GET", "/api/teams", token = user.token)
        assertEquals(200, list.statusCode(), list.body())
        val node = memberNode(list.body(), team.id)
        assertEquals(TeamService.ROLE_MEMBER, node.get("myRole").asText())
        assertFalse(node.get("canManage").asBoolean())

        val me = send("GET", "/api/auth/me", token = user.token)
        assertEquals(200, me.statusCode(), me.body())
        val meTeams = json.readTree(me.body()).get("teams")
        assertTrue(
            meTeams.any { it.get("id").asLong() == team.id && it.get("role").asText() == TeamService.ROLE_MEMBER },
            "me.teams 应包含所在团队：${me.body()}",
        )

        val stranger = createUser()
        val outsider = send("GET", "/api/teams/${team.id}/members", token = stranger.token)
        assertEquals(404, outsider.statusCode(), outsider.body())
    }

    @Test
    fun `改名与权限 - lead 可改 普通成员 403 重名 409`() {
        val team = createTeam()
        val other = createTeam()
        val member = createUser()
        assertEquals(201, send("POST", "/api/teams/${team.id}/members", """{"userId":${member.id},"role":"member"}""").statusCode())

        val memberPatch = send("PATCH", "/api/teams/${team.id}", """{"name":"${uniqueName("x")}"}""", member.token)
        assertEquals(403, memberPatch.statusCode(), memberPatch.body())

        val renamed = send("PATCH", "/api/teams/${team.id}", """{"name":"${team.name}-renamed"}""")
        assertEquals(200, renamed.statusCode(), renamed.body())
        assertEquals("${team.name}-renamed", textField(renamed.body(), "name"))

        val duplicate = send("PATCH", "/api/teams/${team.id}", """{"name":"${other.name}"}""")
        assertEquals(409, duplicate.statusCode(), duplicate.body())
        assertEquals("CONFLICT", codeOf(duplicate.body()))
    }

    @Test
    fun `成员管理 - 最后 lead 409，新增 lead 后可降级`() {
        val team = createTeam()
        val adminId = assertNotNull(users.findByUsername("admin")?.id)

        val demote = send("PATCH", "/api/teams/${team.id}/members/$adminId", """{"role":"member"}""")
        assertEquals(409, demote.statusCode(), demote.body())

        val remove = send("DELETE", "/api/teams/${team.id}/members/$adminId")
        assertEquals(409, remove.statusCode(), remove.body())

        val member = createUser()
        assertEquals(201, send("POST", "/api/teams/${team.id}/members", """{"userId":${member.id},"role":"lead"}""").statusCode())
        assertEquals(200, send("PATCH", "/api/teams/${team.id}/members/$adminId", """{"role":"member"}""").statusCode())
        assertEquals(204, send("DELETE", "/api/teams/${team.id}/members/$adminId").statusCode())

        // member 此时是 team lead；目标用户不存在 → 404
        val notMember = send("PATCH", "/api/teams/${team.id}/members/${member.id + 99999}", """{"role":"member"}""", member.token)
        assertEquals(404, notMember.statusCode(), notMember.body())
    }

    @Test
    fun `删除团队 - 有订阅 409，取消后成功`() {
        val team = createTeam()
        val project = projects.create(orgs.defaultOrgId(), "团队订阅项目", "team-sub-${uniqueSuffix()}")
        val repo = repos.register(assertNotNull(project.id), "file:///team-api-${uniqueSuffix()}", "main")
        subscriptions.subscribe(repo.id, null, "GET", "/pets", teamId = team.id)

        val blocked = send("DELETE", "/api/teams/${team.id}")
        assertEquals(409, blocked.statusCode(), blocked.body())
        assertEquals("CONFLICT", codeOf(blocked.body()))

        val sub = subscriptions.list(repo.id).single()
        subscriptions.unsubscribe(assertNotNull(sub.id))

        assertEquals(204, send("DELETE", "/api/teams/${team.id}").statusCode())
        assertEquals(404, send("GET", "/api/teams/${team.id}").statusCode())
    }

    @Test
    fun `跨组织团队 - 404`() {
        val slug = "org-${uniqueSuffix()}"
        val otherOrg = orgRepos.save(OrgEntity(name = slug, slug = slug, createdAt = Instant.now()))
        val otherTeam = teamService.create(assertNotNull(otherOrg.id), uniqueName("other-team"))

        val resp = send("GET", "/api/teams/${assertNotNull(otherTeam.id)}")
        assertEquals(404, resp.statusCode(), resp.body())
        assertEquals("NOT_FOUND", codeOf(resp.body()))
    }
}
