package io.spechub.server.service

import io.spechub.server.web.AuthService
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import io.spechub.spec.index.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 阶段 B（B2）：团队 CRUD / 成员与最后 lead 保护 / 订阅删除保护 / 旧名解析。 */
@SpringBootTest
class TeamServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var teams: TeamService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var repos: RepoService

    @Autowired
    lateinit var subscriptions: SubscriptionService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-teamtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_team_svc")
        }
    }

    private fun uniqueName(prefix: String): String {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(10)
        return "$prefix-$suffix"
    }

    private fun newUser(): Long =
        assertNotNull(
            users
                .createUserWithMembership(
                    uniqueName("member"),
                    "",
                    AuthService.sha256(UUID.randomUUID().toString()),
                    null,
                    Role.VIEWER,
                ).id,
        )

    @Test
    fun `团队 CRUD - 名称忽略大小写唯一`() {
        val orgId = orgs.defaultOrgId()
        val name = uniqueName("core")
        val team = teams.create(orgId, " $name ", "描述")
        val teamId = assertNotNull(team.id)
        assertEquals(name, team.name, "名称应 trim")
        assertEquals(teamId, teams.findByName(orgId, name.uppercase())?.id, "名称查重忽略大小写")
        assertFailsWith<IllegalArgumentException> { teams.create(orgId, name.uppercase()) }

        val renamed = teams.update(teamId, orgId, name = "$name-renamed")
        assertEquals("$name-renamed", renamed.name)
        assertEquals(0L, teams.subscriptionCount(teamId))
        val other = teams.create(orgId, uniqueName("other"))
        assertFailsWith<IllegalArgumentException> { teams.update(teamId, orgId, name = other.name) }
    }

    @Test
    fun `团队成员 - 非组织成员拒绝且最后一个 lead 不能移除`() {
        val orgId = orgs.defaultOrgId()
        val team = teams.create(orgId, uniqueName("team"))
        val teamId = assertNotNull(team.id)
        val lead = newUser()
        val member = newUser()

        teams.addMember(teamId, orgId, lead, TeamService.ROLE_LEAD)
        teams.addMember(teamId, orgId, member, TeamService.ROLE_MEMBER)
        assertEquals(TeamService.ROLE_LEAD, teams.roleInTeam(lead, teamId))
        assertEquals(2L, teams.memberCount(teamId))
        assertEquals(listOf(TeamService.ROLE_MEMBER), teams.teamsOf(member).map { it.role })

        // 最后一个 lead 不能降级 / 移除
        assertFailsWith<IllegalStateException> { teams.addMember(teamId, orgId, lead, TeamService.ROLE_MEMBER) }
        assertFailsWith<IllegalStateException> { teams.removeMember(teamId, orgId, lead) }

        // 新增第二个 lead 后可降级并移除第一个
        teams.addMember(teamId, orgId, member, TeamService.ROLE_LEAD)
        teams.addMember(teamId, orgId, lead, TeamService.ROLE_MEMBER)
        assertEquals(TeamService.ROLE_MEMBER, teams.roleInTeam(lead, teamId))
        teams.removeMember(teamId, orgId, lead)
        assertEquals(1L, teams.memberCount(teamId))

        // 非组织成员不能加入
        assertFailsWith<IllegalArgumentException> { teams.addMember(teamId, orgId, -999L) }
        assertFailsWith<IllegalArgumentException> { teams.addMember(teamId, orgId, lead, "owner") }
    }

    @Test
    fun `有订阅的团队不能删除`() {
        val orgId = orgs.defaultOrgId()
        val team = teams.create(orgId, uniqueName("sub-team"))
        val teamId = assertNotNull(team.id)
        val repo = repos.register("default", "file:///team-delete-${UUID.randomUUID()}", "main")
        subscriptions.subscribe(repo.id, null, "GET", "/pets", teamId = teamId)
        assertEquals(1L, teams.subscriptionCount(teamId))

        assertFailsWith<IllegalStateException> { teams.delete(teamId, orgId) }

        val sub = subscriptions.list(repo.id).single()
        subscriptions.unsubscribe(assertNotNull(sub.id))
        teams.delete(teamId, orgId)
        assertNull(teams.get(teamId, orgId))
    }

    @Test
    fun `resolveOrCreate - 幂等且忽略大小写`() {
        val orgId = orgs.defaultOrgId()
        val name = uniqueName("legacy")
        val first = teams.resolveOrCreate(orgId, name)
        val second = teams.resolveOrCreate(orgId, " $name ")
        assertEquals(first.id, second.id)
        assertEquals(1, teams.list(orgId).count { it.name == name })
    }

    @Test
    fun `删除用户清理团队成员关系`() {
        val orgId = orgs.defaultOrgId()
        val team = teams.create(orgId, uniqueName("cleanup"))
        val teamId = assertNotNull(team.id)
        val user = newUser()
        teams.addMember(teamId, orgId, user)
        assertEquals(1L, teams.memberCount(teamId))

        users.deleteUser(user)

        assertEquals(0L, teams.memberCount(teamId), "删除用户应清理 team_member")
    }

    @Test
    fun `跨组织团队不可见`() {
        val orgId = orgs.defaultOrgId()
        val team = teams.create(orgId, uniqueName("private"))
        assertNull(teams.get(assertNotNull(team.id), orgId + 1))
    }
}
