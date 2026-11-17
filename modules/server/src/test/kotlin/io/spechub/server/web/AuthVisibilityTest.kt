package io.spechub.server.web

import io.spechub.server.repository.ProjectEntity
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.UserService
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import io.spechub.spec.index.UserRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 阶段 A：org 上下文、org_admin 优先级、项目可见性（private/internal/public × draft/release）。 */
@SpringBootTest
class AuthVisibilityTest : TestDbIsolation() {
    @Autowired
    lateinit var auth: AuthService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var orgs: OrgService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-authtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_auth_vis")
        }
    }

    private fun createProject(visibility: String): ProjectServiceTestProject {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val project = projects.create(orgs.defaultOrgId(), "vis $suffix", "v-$suffix", visibility = visibility)
        return ProjectServiceTestProject(assertNotNull(project.id), project)
    }

    private fun createOrgMember(): UserRecord {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val user = users.createUserWithMembership("u-$suffix", "", "hash-$suffix", null, Role.VIEWER)
        return assertNotNull(users.findById(assertNotNull(user.id)))
    }

    private data class ProjectServiceTestProject(
        val id: Long,
        val entity: ProjectEntity,
    )

    @Test
    fun `org admin 可读 private draft 且按 owner 判定`() {
        val admin = assertNotNull(users.bootstrapAdmin("hash-" + UUID.randomUUID()))
        val project = createProject("private")
        assertTrue(auth.canReadProject(admin, project.entity, Stage.DRAFT))
        assertEquals(Role.OWNER, auth.effectiveRole(admin, project.id))
        assertTrue(auth.isOrgAdmin(admin, orgs.defaultOrgId()))
    }

    @Test
    fun `项目成员可读 private draft 并按项目角色判定`() {
        val user = createOrgMember()
        val project = createProject("private")
        users.setMembership(assertNotNull(user.id), project.id, Role.EDITOR)
        val fresh = assertNotNull(users.findById(assertNotNull(user.id)))

        assertTrue(auth.canReadProject(fresh, project.entity, Stage.DRAFT))
        assertEquals(Role.EDITOR, auth.effectiveRole(fresh, project.id))
    }

    @Test
    fun `组织成员非项目成员仅读 internal public release`() {
        val user = createOrgMember()
        val internal = createProject("internal")
        val public = createProject("public")
        val private = createProject("private")

        assertTrue(auth.canReadProject(user, internal.entity, Stage.RELEASE))
        assertFalse(auth.canReadProject(user, internal.entity, Stage.DRAFT))
        assertTrue(auth.canReadProject(user, public.entity, Stage.RELEASE))
        assertFalse(auth.canReadProject(user, private.entity, Stage.RELEASE))
    }

    @Test
    fun `匿名仅读 public release`() {
        val public = createProject("public")
        val internal = createProject("internal")

        assertTrue(auth.canReadProject(null, public.entity, Stage.RELEASE))
        assertFalse(auth.canReadProject(null, public.entity, Stage.DRAFT))
        assertFalse(auth.canReadProject(null, internal.entity, Stage.RELEASE))
    }
}
