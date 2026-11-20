package io.spechub.server.web

import io.spechub.server.security.AppUserPrincipal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.core.session.SessionRegistry
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C9：Spring Session Data Redis 行为。
 * - `PRINCIPAL_NAME` 索引与 `SessionRegistry`（设备列表 / 吊销基础）；
 * - 会话键写入 Redis 且带空闲 TTL；删除后键清除；
 * - 未知会话 Cookie 不降级为匿名。
 *
 * 使用 TestManagementDb 注册的共享 Redis，namespace 按 schema 隔离（`spechub:session:t_session`）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=session-admin-token",
    ],
)
class RedisSessionTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var sessionRegistry: SessionRegistry

    @Autowired
    lateinit var sessionRepository: FindByIndexNameSessionRepository<out Session>

    @Autowired
    lateinit var redis: StringRedisTemplate

    companion object {
        private const val ADMIN = "session-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-session-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_session")
        }
    }

    private fun login(): HttpClient {
        val client =
            HttpClient
                .newBuilder()
                .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build()
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"token":"$ADMIN"}"""))
                .header("Content-Type", "application/json")
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return client
    }

    @Test
    fun `按 principal 列出会话并可删除`() {
        login()
        login()

        val sessions = sessionRepository.findByPrincipalName("admin")
        assertEquals(2, sessions.size, "两次登录应产生两个 Spring Session")

        val principal = AppUserPrincipal(userId = 1, username = "admin")
        val registered = sessionRegistry.getAllSessions(principal, false)
        assertEquals(2, registered.size, "SessionRegistry 按 principal 索引应返回两个会话")

        sessionRepository.deleteById(sessions.keys.first())
        assertEquals(1, sessionRepository.findByPrincipalName("admin").size)
        assertEquals(1, sessionRegistry.getAllSessions(principal, false).size)

        val remaining = sessionRegistry.getAllSessions(principal, false).single()
        assertTrue(remaining.sessionId.isNotBlank())
        assertTrue(!remaining.isExpired)
    }

    @Test
    fun `会话键写入 Redis 且带空闲 TTL - 删除后清除`() {
        // 同一测试类的多个用例共享会话空间：先清掉前序用例残留
        sessionRepository.findByPrincipalName("admin").keys.forEach { sessionRepository.deleteById(it) }
        login()
        val session = sessionRepository.findByPrincipalName("admin").values.single()
        val key = "spechub:session:t_session:sessions:${session.id}"

        assertTrue(redis.hasKey(key), "会话应写入 Redis：$key")
        val ttl = redis.getExpire(key, TimeUnit.SECONDS)
        // Spring Session 的分钟级过期簿会向上取整到下一分钟；这里只验证 TTL 已设置且约为 24h
        assertTrue(ttl > 0 && ttl <= 24 * 3600 + 600, "空闲 TTL 应约为 24h（实际 $ttl）")
        assertEquals(java.time.Duration.ofHours(24), session.maxInactiveInterval, "默认空闲超时 24h")

        sessionRepository.deleteById(session.id)
        // 物理键清理由分钟级过期簿异步完成；删除后仓储必须立即不可读
        assertNull(sessionRepository.findById(session.id), "删除会话后不可再读取")
    }

    @Test
    fun `未知会话 Cookie 不降级为匿名`() {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/projects"))
                .header("Cookie", "spechub_session=unknown-session-id")
                .GET()
                .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, response.statusCode(), response.body())
    }
}

/**
 * C9：Redis 不可用时认证 fail-closed（不降级为匿名 / 内存会话）。
 *
 * 本类使用独立 Redis 容器：先验证登录可用，再停止容器，确认登录 503 且会话 Cookie 请求
 * 不会被当作匿名放行。共享 Redis 容器不受影响。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=redis-down-token",
        // 停容器后快速失败，避免 Lettuce 默认长超时拖慢测试
        "spring.data.redis.timeout=1s",
        "spring.data.redis.connect-timeout=1s",
        "spring.data.redis.lettuce.shutdown-timeout=100ms",
    ],
)
class RedisUnavailableTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-redis-down-" + System.nanoTime())
        private val redis =
            GenericContainer(DockerImageName.parse("redis:7.4.2-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            TestManagementDb.register(registry, "t_redis_down")
            // 覆盖共享 Redis：本类使用可随时停止的独立容器
            registry.add("spring.data.redis.url") { "redis://${redis.host}:${redis.getMappedPort(6379)}" }
            registry.add("spring.session.data.redis.namespace") { "spechub:session:t_redis_down" }
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
    }

    private fun login(): HttpResponse<String> =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI("http://localhost:$port/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"token":"redis-down-token"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

    @Test
    fun `Redis 不可用时登录与会话均 fail-closed`() {
        // 基线：Redis 可用时真实登录成功
        val before = login()
        assertEquals(200, before.statusCode(), before.body())

        redis.stop()

        val during = login()
        assertTrue(
            during.statusCode() in 500..599,
            "Redis 不可用时登录必须 fail-closed（5xx），实际 ${during.statusCode()}：${during.body()}",
        )
        assertTrue(during.headers().firstValue("Set-Cookie").isEmpty, "Redis 不可用时不应建立会话 Cookie")

        val withCookie =
            HttpClient
                .newHttpClient()
                .send(
                    HttpRequest
                        .newBuilder(URI("http://localhost:$port/api/projects"))
                        .header("Cookie", "spechub_session=whatever")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
        assertTrue(
            withCookie.statusCode() != 200,
            "Redis 不可用时不应返回 200（降级为匿名）：${withCookie.statusCode()} ${withCookie.body()}",
        )
    }
}
