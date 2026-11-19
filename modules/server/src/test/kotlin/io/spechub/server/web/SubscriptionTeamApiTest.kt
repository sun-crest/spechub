package io.spechub.server.web

import io.spechub.server.repository.NotificationEntity
import io.spechub.server.repository.NotificationRepository
import io.spechub.server.repository.OrgEntity
import io.spechub.server.repository.OrgRepository
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.SubscriptionService
import io.spechub.server.service.TeamService
import io.spechub.server.service.UserService
import io.spechub.spec.index.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
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
class SubscriptionTeamApiTest : TestDbIsolation() {
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

    @Autowired
    lateinit var subscriptions: SubscriptionService

    @Autowired
    lateinit var notificationRepos: NotificationRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

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

    @BeforeEach
    fun clean() {
        clearManagementTables(jdbc)
    }

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

    private fun addNotification(
        teamId: Long,
        repoId: Long,
        path: String = "/pets",
    ) {
        val team = assertNotNull(teamService.get(teamId, orgs.defaultOrgId()))
        subscriptions.addNotification(teamId, team.name, repoId, "GET", path, "结构变更：GET $path")
    }

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

    @Test
    fun `通知只对团队成员可见 - 非成员 404 且 org_admin 可审计`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        val lead = createUser(projectId)
        val member = createUser(projectId)
        val outsider = createUser(projectId)
        assertEquals(201, addMember(teamId, lead.id, TeamService.ROLE_LEAD))
        assertEquals(201, addMember(teamId, member.id, TeamService.ROLE_MEMBER))
        addNotification(teamId, repoId, "/pets")
        addNotification(teamId, repoId, "/pets/{id}")

        for (user in listOf(lead, member)) {
            val own = send("GET", "/api/subscriptions/notifications", token = user.token)
            assertEquals(200, own.statusCode(), own.body())
            assertEquals(2, json.readTree(own.body()).size(), "团队成员可见团队通知")
        }

        val outsiderAll = send("GET", "/api/subscriptions/notifications", token = outsider.token)
        assertEquals(200, outsiderAll.statusCode(), outsiderAll.body())
        assertEquals(0, json.readTree(outsiderAll.body()).size(), "非团队成员看不到团队通知")

        val outsiderTeam = send("GET", "/api/subscriptions/notifications?teamId=$teamId", token = outsider.token)
        assertEquals(404, outsiderTeam.statusCode(), outsiderTeam.body())

        val adminTeam = send("GET", "/api/subscriptions/notifications?teamId=$teamId")
        assertEquals(200, adminTeam.statusCode(), adminTeam.body())
        assertEquals(2, json.readTree(adminTeam.body()).size(), "org_admin 可审计团队通知")
    }

    @Test
    fun `历史通知 team_id 为空 - 仅 org_admin 可见`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        val lead = createUser(projectId)
        assertEquals(201, addMember(teamId, lead.id, TeamService.ROLE_LEAD))
        addNotification(teamId, repoId)
        notificationRepos.save(
            NotificationEntity(
                teamId = null,
                team = "legacy",
                repoId = repoId,
                orgId = orgs.defaultOrgId(),
                method = "GET",
                path = "/legacy",
                summary = "历史通知",
                createdAt = Instant.now(),
            ),
        )

        val leadList = send("GET", "/api/subscriptions/notifications", token = lead.token)
        assertEquals(200, leadList.statusCode(), leadList.body())
        assertEquals(1, json.readTree(leadList.body()).size(), "普通用户看不到 team_id 为空的历史通知")

        val adminList = send("GET", "/api/subscriptions/notifications")
        assertEquals(200, adminList.statusCode(), adminList.body())
        assertEquals(2, json.readTree(adminList.body()).size(), "org_admin 可审计全组织通知")
    }

    @Test
    fun `通知跨团队隔离 - 指定他人 teamId 404`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val team1 = createTeam()
        val team2 = createTeam()
        val user1 = createUser(projectId)
        val user2 = createUser(projectId)
        assertEquals(201, addMember(team1, user1.id, TeamService.ROLE_LEAD))
        assertEquals(201, addMember(team2, user2.id, TeamService.ROLE_LEAD))
        addNotification(team1, repoId, "/pets")
        addNotification(team2, repoId, "/pets/{id}")

        val list1 = send("GET", "/api/subscriptions/notifications", token = user1.token)
        val list2 = send("GET", "/api/subscriptions/notifications", token = user2.token)
        assertEquals(200, list1.statusCode(), list1.body())
        assertEquals(200, list2.statusCode(), list2.body())
        assertEquals(1, json.readTree(list1.body()).size())
        val path1 =
            json
                .readTree(list1.body())
                .get(0)
                ?.get("path")
                ?.asText()
        assertEquals("/pets", path1)
        assertEquals(1, json.readTree(list2.body()).size())
        val path2 =
            json
                .readTree(list2.body())
                .get(0)
                ?.get("path")
                ?.asText()
        assertEquals("/pets/{id}", path2)

        val cross = send("GET", "/api/subscriptions/notifications?teamId=$team2", token = user1.token)
        assertEquals(404, cross.statusCode(), cross.body())
    }

    @Test
    fun `团队删除后历史通知保留 - 仅 org_admin 可见`() {
        val projectId = createProject()
        val repoId = createRepo(projectId)
        val teamId = createTeam()
        val user = createUser(projectId)
        assertEquals(201, addMember(teamId, user.id, TeamService.ROLE_LEAD))
        addNotification(teamId, repoId, "/legacy")

        assertEquals(204, send("DELETE", "/api/teams/$teamId").statusCode())

        val userList = send("GET", "/api/subscriptions/notifications", token = user.token)
        assertEquals(200, userList.statusCode(), userList.body())
        assertEquals(0, json.readTree(userList.body()).size(), "团队删除后普通用户看不到历史通知")

        val adminList = send("GET", "/api/subscriptions/notifications")
        assertEquals(200, adminList.statusCode(), adminList.body())
        assertEquals(1, json.readTree(adminList.body()).size(), "历史通知保留（team_id 置空）")
        val legacyPath =
            json
                .readTree(adminList.body())
                .get(0)
                ?.get("path")
                ?.asText()
        assertEquals("/legacy", legacyPath)
    }
}
