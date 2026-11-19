package io.spechub.server.security

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C3：Redis 登录限流（固定窗口 / 锁定 / 成功清零 / Redis 不可用 fail-closed）。 */
@SpringBootTest
class RedisLoginRateLimiterTest : TestDbIsolation() {
    @Autowired
    lateinit var limiter: RedisLoginRateLimiter

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-ratelimit-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_rate_limit")
        }

        private fun unique(prefix: String): String =
            "$prefix-" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
    }

    @Test
    fun `同一标识 5 次失败锁定 15 分钟 - 成功清零`() {
        val identifier = unique("user")
        val ip = unique("ip")

        assertNull(limiter.check(identifier, ip))
        repeat(4) { limiter.onFailure(identifier, ip) }
        assertNull(limiter.check(identifier, ip), "4 次失败未锁定")

        limiter.onFailure(identifier, ip)
        val retry = assertNotNull(limiter.check(identifier, ip), "第 5 次失败后锁定")
        assertTrue(retry in 800..900, "锁定时长约 15 分钟：$retry")

        limiter.onSuccess(identifier, ip)
        assertNull(limiter.check(identifier, ip), "成功登录清零")
    }

    @Test
    fun `同 IP 20 次失败锁定`() {
        val ip = unique("ip")
        repeat(19) { attempt ->
            limiter.onFailure(unique("user-$attempt"), ip)
        }
        assertNull(limiter.check(unique("fresh"), ip), "19 次失败未锁定 IP")
        limiter.onFailure(unique("user-19"), ip)
        assertNotNull(limiter.check(unique("fresh"), ip), "第 20 次失败后锁定 IP")
    }

    @Test
    fun `Redis 不可用时 fail-closed`() {
        val factory = LettuceConnectionFactory("127.0.0.1", 1)
        factory.afterPropertiesSet()
        factory.start()
        try {
            val template = StringRedisTemplate(factory)
            template.afterPropertiesSet()
            val failing = RedisLoginRateLimiter(template)
            assertFailsWith<LoginRateLimitUnavailableException>("Redis 不可用应 fail-closed") {
                failing.check("user", "ip")
            }
        } finally {
            factory.destroy()
        }
    }
}
