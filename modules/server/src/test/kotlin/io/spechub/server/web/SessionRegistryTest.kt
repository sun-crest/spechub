package io.spechub.server.web

import io.spechub.server.security.AppUserPrincipal
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.core.session.SessionRegistry
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C2：Spring Session Redis 的 principal 索引 / SessionRegistry（设备列表与吊销的基础）。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=session-admin-token",
    ],
)
class SessionRegistryTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var sessionRegistry: SessionRegistry

    @Autowired
    lateinit var sessionRepository: FindByIndexNameSessionRepository<out Session>

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
}
