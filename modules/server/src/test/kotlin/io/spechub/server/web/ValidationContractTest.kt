package io.spechub.server.web

import io.spechub.server.service.ProjectService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R2 —— 声明式校验（规范 §3）验收：
 * - DTO 注解化（`@field:NotBlank`）后：空值/空串分布统一被 Bean Validation 拦截；
 * - 枚举值域类校验（role/type/status/format…）升级为 `VALIDATION` 错误体 + `fieldErrors`；
 * - 校验失败不进入方法体（不产生副作用）。
 *
 * 基建同 ErrorContractTest：RANDOM_PORT + JDK HttpClient + Jackson 3；启用鉴权以便请求通过 filter。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.admin-token=test-contract-token",
        "spechub.polling-interval-ms=3600000",
    ],
)
class ValidationContractTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var projects: ProjectService

    private fun defaultProjectId(): Long = projects.projectIdByName("default") ?: error("default project 未初始化")

    companion object {
        private val TEMPDIR: java.nio.file.Path = kotlin.io.path.createTempDirectory("spechub-validation-test-" + System.nanoTime())
        private const val ADMIN = "test-contract-token"

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            TestManagementDb.register(registry, "t_validation")
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }

        private val json = JsonMapper.builder().build()
    }

    private val client = HttpClient.newHttpClient()

    private fun send(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        builder.header("Authorization", "Bearer $ADMIN")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 无 Bearer：登录请求必须走 Web 链的 JSON login filter。 */
    private fun sendAnon(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String,
    ) = send("POST", path, body)

    private fun get(path: String) = send("GET", path)

    /** 断言：400 + code=VALIDATION + fieldErrors 含指定字段 */
    private fun assertValidation(
        bodyStr: String,
        field: String,
    ) {
        val t = json.readTree(bodyStr)
        assertEquals(400, t.at("/status").asInt(), "应为 400：$bodyStr")
        assertEquals("VALIDATION", t.at("/code").asText(), "应为 VALIDATION：$bodyStr")
        val fields =
            buildList {
                for (f in t.at("/fieldErrors")) add(f.at("/field").asText())
            }
        assertTrue(field in fields, "fieldErrors 应含「$field」：$bodyStr")
    }

    @Test
    fun `AuthController - 空 username 被注解拦截`() {
        val r = post("/api/auth/users", """{"username":"","role":"editor"}""")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "username")
    }

    @Test
    fun `AuthController - 非法 orgRole 值为 VALIDATION 错误体`() {
        val r = post("/api/auth/users", """{"username":"alice","orgRole":"superadmin"}""")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "orgRole")
    }

    @Test
    fun `RepoController - 空 url 被注解拦截（未注册无副作用）`() {
        val r = post("/api/repos", """{"projectId":${defaultProjectId()},"url":""}""")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "url")
        // 校验失败不进方法体：不应创建任何 repo
        assertEquals(0, json.readTree(get("/api/repos").body()).size(), "空 url 不应创建 repo")
    }

    @Test
    fun `ImportController - 空 content 被注解拦截`() {
        val r = post("/api/import/postman", """{"content":""}""")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "content")
    }

    @Test
    fun `SubscriptionController - 空 method 被注解拦截`() {
        val r = post("/api/subscriptions", """{"repoId":1,"method":"","path":"/pets","teamId":1}""")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "method")
    }

    @Test
    fun `ReviewController - 非法 status 值为 VALIDATION 错误体`() {
        val r = get("/api/reviews?status=bogus")
        assertEquals(400, r.statusCode())
        assertValidation(r.body(), "status")
    }

    // ── 阶段 C 新增请求体校验 ───────────────────────────────────────────────

    @Test
    fun `密码登录 - password 与 token 必须且只能提供一个`() {
        val missing = sendAnon("POST", "/api/auth/login", "{}")
        assertEquals(400, missing.statusCode(), missing.body())
        assertEquals("BAD_REQUEST", json.readTree(missing.body()).get("code").asText())

        val both =
            sendAnon(
                "POST",
                "/api/auth/login",
                """{"username":"admin","password":"some-password","token":"some-token"}""",
            )
        assertEquals(400, both.statusCode(), both.body())
        assertEquals("BAD_REQUEST", json.readTree(both.body()).get("code").asText())
    }

    @Test
    fun `用户创建 - 密码策略返回 newPassword 字段`() {
        val r = post("/api/auth/users", """{"username":"weak-user","password":"short"}""")
        assertEquals(400, r.statusCode(), r.body())
        assertValidation(r.body(), "newPassword")
    }

    @Test
    fun `用户令牌 - 空名称与非法有效期`() {
        val blank = post("/api/auth/tokens", """{"name":""}""")
        assertEquals(400, blank.statusCode(), blank.body())
        assertValidation(blank.body(), "name")

        val badDays = post("/api/auth/tokens", """{"name":"ci","expiresInDays":0}""")
        assertEquals(400, badDays.statusCode(), badDays.body())
        assertValidation(badDays.body(), "expiresInDays")
    }

    @Test
    fun `服务账号 - 空名称 非法角色与非法有效期`() {
        val projectId = defaultProjectId()

        val blank = post("/api/service-accounts", """{"projectId":$projectId,"name":""}""")
        assertEquals(400, blank.statusCode(), blank.body())
        assertValidation(blank.body(), "name")

        val badRole = post("/api/service-accounts", """{"projectId":$projectId,"name":"role-check","role":"owner"}""")
        assertEquals(400, badRole.statusCode(), badRole.body())
        assertEquals("VALIDATION", json.readTree(badRole.body()).get("code").asText())

        val badDays = post("/api/service-accounts", """{"projectId":$projectId,"name":"days-check","expiresInDays":0}""")
        assertEquals(400, badDays.statusCode(), badDays.body())
        assertEquals("VALIDATION", json.readTree(badDays.body()).get("code").asText())
    }

    @Test
    fun `审计查询 - 非法时间与结果值`() {
        val badFrom = get("/api/audit?from=not-an-instant")
        assertEquals(400, badFrom.statusCode(), badFrom.body())
        assertValidation(badFrom.body(), "from")

        val badOutcome = get("/api/audit?outcome=maybe")
        assertEquals(400, badOutcome.statusCode(), badOutcome.body())
        assertEquals("VALIDATION", json.readTree(badOutcome.body()).get("code").asText())
    }
}
