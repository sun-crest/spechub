package io.spechub.server.web

import io.spechub.server.security.AppUserPrincipal
import io.spechub.server.security.OpaqueTokenAuthenticationToken
import io.spechub.server.security.ServicePrincipal
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C2：principal 可序列化 / 最小化，服务账号项目隔离，禁用用户即时 401。 */
@SpringBootTest
class AuthPrincipalTest : TestDbIsolation() {
    @Autowired
    lateinit var auth: AuthService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var orgs: OrgService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-principal-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_principal")
        }
    }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `AppUserPrincipal 可序列化且不把密码哈希写入会话`() {
        val principal =
            AppUserPrincipal(
                userId = 7,
                username = "alice",
                displayName = "Alice",
                status = "active",
                mustChangePassword = true,
                passwordHash = "argon2-hash",
            )
        val restored = roundTrip(principal)

        assertEquals(7L, restored.userId)
        assertEquals("alice", restored.username)
        assertTrue(restored.mustChangePassword)
        assertNull(restored.passwordHash, "transient 密码哈希不得随 Java 序列化进入 Redis 会话")
        assertEquals("", restored.password)
    }

    @Test
    fun `ServicePrincipal 命名为 service 前缀`() {
        val principal = ServicePrincipal(1, "ci-bot", 2, 3, Role.EDITOR)
        assertEquals("ci-bot", principal.name)
        assertEquals("service:ci-bot", principal.getName())
    }

    @Test
    fun `服务账号只在其项目内有效 - 跨项目 404 且禁止人类协作端点`() {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(10)
        val orgId = orgs.defaultOrgId()
        val projectA = assertNotNull(projects.create(orgId, "svc-a-$suffix", "svc-a-$suffix").id)
        val projectB = assertNotNull(projects.create(orgId, "svc-b-$suffix", "svc-b-$suffix").id)
        val principal = ServicePrincipal(accountId = 1, name = "ci-bot", orgId = orgId, projectId = projectA, role = Role.VIEWER)
        authenticate(principal)

        val req = MockHttpServletRequest()
        assertEquals(Role.VIEWER, auth.effectiveRoleFor(principal, projectA))
        assertNull(auth.effectiveRoleFor(principal, projectB), "服务账号跨项目无角色")
        assertEquals(setOf(projectA), auth.visibleProjectIds(req, Stage.RELEASE))
        assertEquals("service:ci-bot", auth.currentActor(req))
        assertNull(auth.currentUser(req), "服务账号不是人类用户")

        val cross = assertFailsWith<ApiException> { auth.requireProjectVisible(req, projectB) }
        assertEquals(404, cross.status, "跨项目读统一 404")

        val humanOnly = assertFailsWith<ApiException> { auth.requireHuman(req, "评论") }
        assertEquals(403, humanOnly.status, "服务账号不可用人类协作端点")

        assertEquals("service:ci-bot", auth.requireAuthenticated(req, "导入").username)
    }

    @Test
    fun `禁用用户下一次请求立即 401`() {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(10)
        val user =
            users.createUserWithMembership("disabled-$suffix", "", null, Role.VIEWER)
        val userId = assertNotNull(user.id)
        authenticate(AppUserPrincipal(userId, user.username, user.displayName, "active"))
        assertNotNull(auth.currentUser(MockHttpServletRequest()))

        users.update(userId, status = "disabled")
        val ex = assertFailsWith<ApiException> { auth.currentUser(MockHttpServletRequest()) }
        assertEquals(401, ex.status, "禁用后每请求状态校验兜底为 401")
    }

    private fun authenticate(principal: Any) {
        val authentication = OpaqueTokenAuthenticationToken.authenticated(principal)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
    }

    private fun roundTrip(principal: AppUserPrincipal): AppUserPrincipal {
        val bytes =
            ByteArrayOutputStream().use { out ->
                ObjectOutputStream(out).use { it.writeObject(principal) }
                out.toByteArray()
            }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as AppUserPrincipal
    }
}
