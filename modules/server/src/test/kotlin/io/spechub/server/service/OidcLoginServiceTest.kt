package io.spechub.server.service

import io.spechub.server.repository.UserIdentityEntity
import io.spechub.server.repository.UserIdentityRepository
import io.spechub.server.repository.UserRepository
import io.spechub.server.web.AuthService
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** C6：OIDC 身份绑定 / 邮箱绑定 / 未验证邮箱 / disabled / 重复身份（auto-provision 默认关）。 */
@SpringBootTest
class OidcLoginServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var login: OidcLoginService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var identities: UserIdentityRepository

    @Autowired
    lateinit var auth: AuthService

    @Autowired
    lateinit var passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder

    companion object {
        private const val ISSUER = "https://idp.example/realms/spechub"
        private val TEMPDIR: Path = createTempDirectory("spechub-oidc-" + System.nanoTime())

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
            TestManagementDb.register(registry, "t_oidc")
        }

        private fun claims(
            subject: String,
            email: String?,
            emailVerified: Boolean = true,
            issuer: String = ISSUER,
            name: String? = "OIDC User",
        ): OidcLoginService.OidcClaims {
            val idToken =
                OidcIdToken
                    .withTokenValue("id-token-${UUID.randomUUID()}")
                    .claim("sub", subject)
                    .claim("iss", issuer)
                    .claim("aud", listOf("spechub"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build()
            return OidcLoginService.OidcClaims(
                issuer = issuer,
                subject = subject,
                email = email,
                emailVerified = emailVerified,
                name = name,
                claims = idToken.claims,
                idToken = idToken,
            )
        }
    }

    private fun createLinkedUser(
        email: String,
        subject: String,
    ): Long {
        val username = unique("oidc")
        val user = users.createOrgUser(username = username, displayName = "", email = email)
        val userId = assertNotNull(user.id)
        identities.save(
            UserIdentityEntity(
                userId = userId,
                provider = OidcLoginService.providerFor(ISSUER),
                subject = subject,
                email = email,
                createdAt = Instant.now(),
            ),
        )
        return userId
    }

    @Test
    fun `已绑定身份 - 直接返回本地用户且 principal 可序列化`() {
        val email = "${unique("bound")}@example.com"
        val subject = unique("sub")
        val userId = createLinkedUser(email, subject)

        val principal = login.resolve(claims(subject, email))
        assertEquals(userId, principal.appUserId)
        assertEquals(1, identities.findAllByUserId(userId).size)

        val restored = roundTrip(principal)
        assertEquals(principal.appUserId, restored.appUserId)
        assertEquals(principal.username, restored.username)
        assertEquals(principal.claims["sub"], restored.claims["sub"])
    }

    @Test
    fun `邮箱绑定 - 首次登录绑定既有密码用户且重复登录幂等`() {
        val email = "${unique("bind")}@example.com"
        val username = unique("bind-user")
        val user =
            users.createOrgUser(
                username = username,
                displayName = "",
                email = email,
                passwordHash = checkNotNull(passwordEncoder.encode("user-password-123")),
            )
        val userId = assertNotNull(user.id)
        val subject = unique("sub")

        val principal = login.resolve(claims(subject, email))
        assertEquals(userId, principal.appUserId)
        assertEquals(username, principal.username)
        assertEquals(1, identities.findAllByUserId(userId).size)
        assertNotNull(users.passwordHash(userId), "OIDC 绑定不覆盖既有密码")

        val again = login.resolve(claims(subject, email))
        assertEquals(userId, again.appUserId)
        assertEquals(1, identities.findAllByUserId(userId).size, "重复登录不产生重复绑定")
    }

    @Test
    fun `未验证邮箱拒绝`() {
        val email = "${unique("unverified")}@example.com"
        val ex = assertFailsWith<OidcLoginException> { login.resolve(claims(unique("sub"), email, emailVerified = false)) }
        assertEquals("OIDC 邮箱未验证", ex.message)
        assertNull(userRepository.findByEmail(email), "未验证邮箱不建号")
    }

    @Test
    fun `auto-provision 默认关闭 - 未绑定用户拒绝`() {
        val email = "${unique("noprovision")}@example.com"
        assertFailsWith<OidcLoginException> { login.resolve(claims(unique("sub"), email)) }
        assertNull(userRepository.findByEmail(email))
    }

    @Test
    fun `禁用用户拒绝`() {
        val email = "${unique("disabled")}@example.com"
        val subject = unique("sub")
        val userId = createLinkedUser(email, subject)
        users.update(userId, status = "disabled")

        val ex = assertFailsWith<OidcLoginException> { login.resolve(claims(subject, email)) }
        assertEquals("账号不可用", ex.message)
    }

    @Test
    fun `OIDC principal 可被 AuthService 识别为人类用户`() {
        val email = "${unique("auth")}@example.com"
        val subject = unique("sub")
        val userId = createLinkedUser(email, subject)
        val principal = login.resolve(claims(subject, email))

        val authentication = OAuth2AuthenticationToken(principal, emptyList(), "spechub")
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        try {
            assertEquals(userId, auth.currentUser()?.id)
            assertEquals(principal.username, auth.currentActor())
            assertEquals(principal.username, auth.requireHuman(MockHttpServletRequest(), "操作").username)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun roundTrip(principal: io.spechub.server.security.OidcAppUserPrincipal): io.spechub.server.security.OidcAppUserPrincipal {
        val bytes =
            ByteArrayOutputStream().use { out ->
                ObjectOutputStream(out).use { it.writeObject(principal) }
                out.toByteArray()
            }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as io.spechub.server.security.OidcAppUserPrincipal
    }
}
