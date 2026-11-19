package io.spechub.server.web

import io.spechub.server.repository.OrgEntity
import io.spechub.server.repository.OrgRepository
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 B（B4）：订阅写入/取消的团队 lead 权限、teamId 契约与可见性。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-sub-team-admin",
    ],
)
class SubscriptionTeamApiTest {
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
    lateinit var teamService: TeamService

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private const val ADMIN = "test-sub-team-admin"
        private val TEMPDIR: Path = createTempDirectory("spechub-subteam-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_sub_team_api")
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
            .take(10)

    private fun createUser(projectId: Long?): TestUser {
        val suffix = uniqueSuffix()
        val rawToken = "sub-team-user-$suffix"
        val user = users.createUserWithMembership("sub-team-$suffix", "", AuthService.sha256(rawToken), projectId, Role.VIEWER)
        return TestUser(assertNotNull(user.id), rawToken)
    }

    private fun createProject(): Long = assertNotNull(projects.create(orgs.defaultOrgId(), "订阅权限项目-${uniqueSuffix()}", "sub-team-${uniqueSuffix()}").id)

    private fun createRepo(projectId: Long): Long = repos.register(projectId, "file:///sub-team-${uniqueSuffix()}", "main").id

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

    private fun createTeam(): Long {
        val resp = send("POST", "/api/teams", """{"name":"sub-team-${uniqueSuffix()}"}""")
        assertEquals(201, resp.statusCode(), resp.body())
        return longField(resp.body(), "id")
    }

    private fun addMember(
        teamId: Long,
        userId: Long,
        role: String,
    ): Int = send("POST", "/api/teams/$teamId/members", """{"userId":$userId,"role":"$role"}""").statusCode()

    private fun subscribe(
        repoId: Long,
        teamId: Long,
        token: String,
    ): HttpResponse<String> = send("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets","teamId":$teamId}""", token)

    @Test
    fun `订阅写权限 - 仅 team lead 可订阅与取消`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        val lead = createUser(projectId)
        val member = createUser(projectId)
        assertEquals(201, addMember(teamId, member.id, TeamService.ROLE_MEMBER))

        // 非团队成员：团队可见但非 lead → 403
        assertEquals(403, subscribe(repoId, teamId, lead.token).statusCode(), "非 lead 不应能订阅")

        assertEquals(201, addMember(teamId, lead.id, TeamService.ROLE_LEAD))
        val sub = subscribe(repoId, teamId, lead.token)
        assertEquals(200, sub.statusCode(), sub.body())
        val subId = longField(sub.body(), "id")

        // member 不能取消团队订阅
        assertEquals(403, send("DELETE", "/api/subscriptions/$subId", token = member.token).statusCode())
        // lead 可以取消
        assertEquals(204, send("DELETE", "/api/subscriptions/$subId", token = lead.token).statusCode())
    }

    @Test
    fun `团队改名后订阅仍有效且显示新名`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        val lead = createUser(projectId)
        assertEquals(201, addMember(teamId, lead.id, TeamService.ROLE_LEAD))
        assertEquals(200, subscribe(repoId, teamId, lead.token).statusCode())

        val newName = "renamed-${uniqueSuffix()}"
        assertEquals(200, send("PATCH", "/api/teams/$teamId", """{"name":"$newName"}""").statusCode())

        val list = send("GET", "/api/subscriptions?repoId=$repoId", token = lead.token)
        assertEquals(200, list.statusCode(), list.body())
        val row = json.readTree(list.body()).first { it.get("teamId").asLong() == teamId }
        assertEquals(newName, row.get("teamName").asText(), "订阅应显示团队当前名")
    }

    @Test
    fun `repo 不可见或跨组织团队均 404`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        // 非项目成员：private 项目 release 不可见
        val outsider = createUser(null)
        assertEquals(201, addMember(teamId, outsider.id, TeamService.ROLE_LEAD))
        val invisible = subscribe(repoId, teamId, outsider.token)
        assertEquals(404, invisible.statusCode(), invisible.body())

        // 其他组织的团队：按当前组织解析不到 → 404
        val slug = "org-${uniqueSuffix()}"
        val otherOrg = orgRepos.save(OrgEntity(name = slug, slug = slug, createdAt = Instant.now()))
        val otherTeam = teamService.create(assertNotNull(otherOrg.id), "other-${uniqueSuffix()}")
        val cross = subscribe(repoId, assertNotNull(otherTeam.id), ADMIN)
        assertEquals(404, cross.statusCode(), cross.body())
    }

    @Test
    fun `旧字段与缺 teamId - 400`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)

        val missing = send("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets"}""")
        assertEquals(400, missing.statusCode(), missing.body())
        assertEquals("VALIDATION", codeOf(missing.body()))
        assertTrue(missing.body().contains("teamId"))

        val legacy = send("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets","team":"core"}""")
        assertEquals(400, legacy.statusCode(), legacy.body())

        val legacyQuery = send("GET", "/api/subscriptions/notifications?team=core")
        assertEquals(400, legacyQuery.statusCode(), legacyQuery.body())
        assertEquals("BAD_REQUEST", codeOf(legacyQuery.body()))
    }
}
