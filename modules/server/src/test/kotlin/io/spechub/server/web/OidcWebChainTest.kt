package io.spechub.server.web

import io.spechub.server.security.OidcLoginFailureHandler
import io.spechub.server.security.OidcLoginSuccessHandler
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C6：OIDC 只挂 Web 链。
 * - enabled=true 时 `/oauth2/authorization/spechub` 302 到 IdP，带 PKCE / state / nonce；
 * - Bearer API 链与密码 / 令牌登录不受影响；
 * - 成功 / 失败 handler 固定站内重定向（不泄露 IdP 错误详情）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=oidc-admin-token",
        "spechub.oidc.enabled=true",
        "spechub.oidc.issuer-uri=https://idp.example/realms/spechub",
        "spechub.oidc.client-id=spechub",
        "spechub.oidc.client-secret=test-secret",
        "spechub.oidc.authorization-uri=https://idp.example/realms/spechub/authorize",
        "spechub.oidc.token-uri=https://idp.example/realms/spechub/token",
        "spechub.oidc.jwk-set-uri=https://idp.example/realms/spechub/certs",
        "spechub.oidc.redirect-uri=http://localhost:8080/login/oauth2/code/spechub",
    ],
)
class OidcWebChainTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    companion object {
        private const val ADMIN = "oidc-admin-token"
        private val TEMPDIR: Path = createTempDirectory("spechub-oidc-web-" + System.nanoTime())
        private val json = JsonMapper.builder().build()

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_oidc_web")
        }
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `status 与 me 报告 OIDC 可用 - Bearer 链不受影响`() {
        val status = send("GET", "/api/auth/status")
        assertEquals(200, status.statusCode(), status.body())
        val tree = json.readTree(status.body())
        assertTrue(tree.get("oidcEnabled").asBoolean())
        assertEquals("/oauth2/authorization/spechub", tree.get("oidcLoginUrl").asString())

        val me = send("GET", "/api/auth/me", token = ADMIN)
        assertEquals(200, me.statusCode(), me.body())
        assertTrue(json.readTree(me.body()).get("oidcEnabled").asBoolean())
        assertEquals(200, send("GET", "/api/projects", token = ADMIN).statusCode(), "Bearer API 链不受 OIDC 影响")

        val login = send("POST", "/api/auth/login", """{"token":"$ADMIN"}""")
        assertEquals(200, login.statusCode(), "密码 / 令牌登录仍可用：${login.body()}")
    }

    @Test
    fun `授权入口 302 到 IdP 且带 PKCE state nonce`() {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val response =
            client.send(
                HttpRequest.newBuilder(URI("http://localhost:$port/oauth2/authorization/spechub")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(302, response.statusCode(), response.body())
        val location = response.headers().firstValue("Location").orElse("")
        assertTrue(location.startsWith("https://idp.example/realms/spechub/authorize"), "Location=$location")
        assertTrue(location.contains("response_type=code"), "Location=$location")
        assertTrue(location.contains("code_challenge="), "PKCE 必须启用：$location")
        assertTrue(location.contains("code_challenge_method=S256"), "PKCE 必须启用：$location")
        assertTrue(location.contains("state="), "Location=$location")
        assertTrue(location.contains("nonce="), "OIDC nonce：$location")
        assertTrue(location.contains("redirect_uri="), "Location=$location")
    }

    @Test
    fun `成功与失败 handler 只做站内重定向`() {
        val success = MockHttpServletResponse()
        OidcLoginSuccessHandler().onAuthenticationSuccess(
            MockHttpServletRequest(),
            success,
            UsernamePasswordAuthenticationToken.authenticated("oidc-user", null, emptyList()),
        )
        assertEquals(302, success.status)
        assertEquals("/", success.redirectedUrl)

        val failure = MockHttpServletResponse()
        OidcLoginFailureHandler().onAuthenticationFailure(
            MockHttpServletRequest(),
            failure,
            BadCredentialsException("idp detail must not leak"),
        )
        assertEquals(302, failure.status)
        assertEquals("/?loginError=oidc", failure.redirectedUrl)
    }
}
