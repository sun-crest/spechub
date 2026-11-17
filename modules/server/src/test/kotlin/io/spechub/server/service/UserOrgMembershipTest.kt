package io.spechub.server.service

import io.spechub.server.repository.OrgMembershipRepository
import io.spechub.server.repository.UserRepository
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 阶段 A：bootstrap admin / 新用户都写 org_membership；删除用户先清组织关系。 */
@SpringBootTest
class UserOrgMembershipTest : TestDbIsolation() {
    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var orgMemberships: OrgMembershipRepository

    @Autowired
    lateinit var userRepository: UserRepository

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-usertest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_user_org")
        }
    }

    @Test
    fun `bootstrap admin 同时写 org_admin`() {
        val admin = users.bootstrapAdmin("hash-" + UUID.randomUUID())
        val adminId = assertNotNull(admin.id)
        assertEquals(OrgService.ROLE_ADMIN, orgs.orgRole(adminId, orgs.defaultOrgId()))
        assertEquals(true, users.isOrgAdmin(adminId))
    }

    @Test
    fun `创建用户写 org_member - 删除时清理组织关系`() {
        val username =
            "u-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
        val user = users.createUserWithMembership(username, "", "hash-$username", null, "viewer")
        val userId = assertNotNull(user.id)
        assertEquals(OrgService.ROLE_MEMBER, orgs.orgRole(userId, orgs.defaultOrgId()))

        users.deleteUser(userId)
        assertNull(userRepository.findById(userId).orElse(null))
        assertNull(orgMemberships.findByUserIdAndOrgId(userId, orgs.defaultOrgId()))
    }
}
