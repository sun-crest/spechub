package io.spechub.server.service

import io.spechub.server.repository.UserStatus
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import io.spechub.spec.index.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C2：用户令牌签发 / 过期 / 吊销 / legacy / 邀请单次使用 / 禁用即时失效。 */
@SpringBootTest
class UserTokenServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var tokens: UserTokenService

    @Autowired
    lateinit var users: UserService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-usertoken-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_user_token")
        }
    }

    private fun newUser(): Long {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        return checkNotNull(users.createUserWithMembership("token-$suffix", "", null, Role.VIEWER).id)
    }

    @Test
    fun `签发 API 令牌 - 只存 hash 与前缀且可解析`() {
        val userId = newUser()
        val issued = tokens.issue(userId, "ci", UserTokenService.PURPOSE_API, Instant.now().plusSeconds(3600))

        assertTrue(issued.raw.startsWith(UserTokenService.PREFIX_USER), "用户令牌 shu_ 前缀")
        assertEquals("shu_" + issued.raw.removePrefix("shu_").take(8), issued.entity.tokenPrefix)
        assertTrue(issued.entity.tokenHash != issued.raw, "库中不得存明文")
        assertEquals(UserTokenService.sha256(issued.raw), issued.entity.tokenHash)

        val resolved = assertNotNull(tokens.resolveLogin(issued.raw))
        assertEquals(userId, resolved.user.id)
        assertNotNull(resolved.token.lastUsedAt, "解析时写 last_used_at")
        assertNull(tokens.resolveLogin("shu_invalid"))
    }

    @Test
    fun `过期与吊销立即拒绝`() {
        val userId = newUser()
        val expired = tokens.issue(userId, "expired", UserTokenService.PURPOSE_API, Instant.now().minusSeconds(1))
        assertNull(tokens.resolveLogin(expired.raw), "过期令牌不可用")

        val active = tokens.issue(userId, "revoke", UserTokenService.PURPOSE_API, Instant.now().plusSeconds(3600))
        assertNotNull(tokens.resolveLogin(active.raw))
        assertTrue(tokens.revoke(checkNotNull(active.entity.id), userId))
        assertNull(tokens.resolveLogin(active.raw), "吊销立即生效")
    }

    @Test
    fun `legacy 无过期 - replaceLegacy 使旧 admin-token 立即失效`() {
        val userId = newUser()
        tokens.replaceLegacy(userId, "old-admin-token")
        assertNotNull(tokens.resolveLogin("old-admin-token"))

        tokens.replaceLegacy(userId, "new-admin-token")
        assertNull(tokens.resolveLogin("old-admin-token"), "旧 bootstrap token 失效")
        assertNotNull(tokens.resolveLogin("new-admin-token"))
    }

    @Test
    fun `邀请令牌不能登录 - 只能设置密码且单次使用`() {
        val userId = newUser()
        val invite = tokens.issue(userId, "invite", UserTokenService.PURPOSE_INVITE, Instant.now().plusSeconds(3600))

        assertNull(tokens.resolveLogin(invite.raw), "邀请令牌不可登录")
        assertNotNull(tokens.resolveSetup(invite.raw))

        tokens.markUsed(checkNotNull(invite.entity.id))
        assertNull(tokens.resolveSetup(invite.raw), "邀请令牌只能使用一次")
    }

    @Test
    fun `轮换令牌 - 旧令牌立即失效且新令牌可用`() {
        val userId = newUser()
        val issued = tokens.issue(userId, "ci", UserTokenService.PURPOSE_API, Instant.now().plusSeconds(3600))
        val rotated = assertNotNull(tokens.rotate(checkNotNull(issued.entity.id), userId))

        assertNull(tokens.resolveLogin(issued.raw), "轮换后旧令牌失效")
        assertNotNull(tokens.resolveLogin(rotated.raw), "新令牌立即可用")
        assertTrue(rotated.raw != issued.raw)
    }

    @Test
    fun `禁用用户后令牌立即失效`() {
        val userId = newUser()
        val issued = tokens.issue(userId, "ci", UserTokenService.PURPOSE_API, Instant.now().plusSeconds(3600))
        assertNotNull(tokens.resolveLogin(issued.raw))

        users.update(userId, status = UserStatus.DISABLED)
        assertNull(tokens.resolveLogin(issued.raw), "禁用用户所有令牌立即失效")

        users.update(userId, status = UserStatus.ACTIVE)
        assertNotNull(tokens.resolveLogin(issued.raw), "启用后令牌本身未吊销则恢复（阶段 C 语义）")
    }
}
