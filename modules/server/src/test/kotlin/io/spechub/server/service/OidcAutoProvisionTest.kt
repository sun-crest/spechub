package io.spechub.server.service

import io.spechub.server.repository.UserIdentityRepository
import io.spechub.server.repository.UserRepository
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** C6：OIDC auto-provision = true / require-verified-email = false 的建号与密码登录行为。 */
@SpringBootTest(
    properties = [
        "spechub.oidc.auto-provision=true",
        "spechub.oidc.require-verified-email=false",
    ],
)
class OidcAutoProvisionTest : TestDbIsolation() {
    @Autowired
    lateinit var login: OidcLoginService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var identities: UserIdentityRepository

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var authenticationManager: AuthenticationManager

    companion object {
        private const val ISSUER = "https://idp.example/realms/spechub"
        private val TEMPDIR: Path = createTempDirectory("spechub-oidc-prov-" + System.nanoTime())

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
            TestManagementDb.register(registry, "t_oidc_prov")
        }

        private fun claims(
            subject: String,
            email: String?,
            emailVerified: Boolean = false,
            name: String? = "OIDC Provisioned",
        ): OidcLoginService.OidcClaims {
            val idToken =
                OidcIdToken
                    .withTokenValue("id-token-${UUID.randomUUID()}")
                    .claim("sub", subject)
                    .claim("iss", ISSUER)
                    .claim("aud", listOf("spechub"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build()
            return OidcLoginService.OidcClaims(
                issuer = ISSUER,
                subject = subject,
                email = email,
                emailVerified = emailVerified,
                name = name,
                claims = idToken.claims,
                idToken = idToken,
            )
        }
    }

    @Test
    fun `自动建号 - 邮箱 local part 用户名 默认 org_member 且无密码`() {
        val local = unique("alice")
        val email = "$local@example.com"
        val principal = login.resolve(claims(unique("sub"), email))

        val user = assertNotNull(users.findById(principal.appUserId))
        assertEquals(local, user.username)
        assertEquals("OIDC Provisioned", user.displayName)
        assertEquals("active", user.status)
        assertNull(userRepository.findById(principal.appUserId).orElse(null)?.passwordHash, "OIDC-only 用户无密码")
        assertEquals(
            OrgService.ROLE_MEMBER,
            orgs.membership(principal.appUserId, orgs.defaultOrgId())?.orgRole,
            "默认加入配置的组织为 org_member",
        )
        assertEquals(1, identities.findAllByUserId(principal.appUserId).size)

        // 再次登录走已绑定身份，不重复建号
        val again = login.resolve(claims(principal.claims["sub"] as String, email))
        assertEquals(principal.appUserId, again.appUserId)
        assertEquals(1, identities.findAllByUserId(principal.appUserId).size)
    }

    @Test
    fun `用户名冲突追加序号`() {
        val local = unique("conflict")
        users.createOrgUser(username = local, displayName = "", email = "$local@example.com")
        val principal = login.resolve(claims(unique("sub"), "$local@other.example"))
        assertEquals("$local-2", principal.username)
    }

    @Test
    fun `无邮箱拒绝自动建号`() {
        assertFailsWith<OidcLoginException> { login.resolve(claims(unique("sub"), email = null)) }
    }

    @Test
    fun `OIDC-only 用户密码登录统一 401`() {
        val local = unique("oidc-only")
        val principal = login.resolve(claims(unique("sub"), "$local@example.com"))

        assertFailsWith<BadCredentialsException> {
            authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(principal.username, "any-password-123"),
            )
        }
    }
}
