package io.spechub.server.web

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I4 —— RBAC 鉴权与成员分层（由 H6 admin-token 占位升级）：
 * - admin-token bootstrap 平台 admin（owner）；`POST /api/auth/login` 校验（用户名+令牌，token-only 兼容）。
 * - 成员管理端点需 default project admin+；写资源（注册仓库）需 editor+。
 * - 创建 / 改角色 / 重置令牌 / 删除用户 / 删除后令牌失效。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTest : TestDbIsolation() {

    @LocalServerPort
    var port: Int = 0

    companion object {

        /** 每次运行全新临时投影目录（companion 静态字段即时创建，不依赖注入时机） */
        private val TEMPDIR: java.nio.file.Path = kotlin.io.path.createTempDirectory("spechub-test-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
        private val json = JsonMapper.builder().build()
        private const val ADMIN = "test-admin-token-123"
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/authtest-origin")
            if (Files.exists(root)) root.toFile().deleteRecursively()
            val bare = root.resolve("origin").also { Files.createDirectories(it) }
            val cli = GitCli()
            val git = GitStore()
            cli.run(bare, listOf("init", "--bare", "-b", "main")).require("init bare")
            val work = root.resolve("seedwork")
            Files.createDirectories(work.resolve("specs/pet"))
            Files.writeString(
                work.resolve("specs/pet/openapi.yaml"),
                """
                openapi: 3.1.0
                info: { title: Pet Store, version: "1.0.0" }
                paths:
                  /pets:
                    get:
                      operationId: listPets
                      tags: [pets]
                      responses:
                        "200": { description: ok }
                """.trimIndent(),
            )
            cli.run(work, listOf("init", "-b", "main")).require("init")
            git.stage(work, listOf(".")).require("stage")
            git.commit(work, "seed", Identity("tester", "t@spechub.test")).require("commit")
            cli.run(work, listOf("remote", "add", "origin", bare.toAbsolutePath().toString())).require("add remote")
            cli.run(work, listOf("push", "origin", "main")).require("push")
            origin = bare.toAbsolutePath().toString()
        }
    }

    private val client = HttpClient.newHttpClient()

    private fun req(method: String, path: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) b.header("Content-Type", "application/json")
        if (token != null) b.header("Authorization", "Bearer $token")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 干净测床：保证 auth 相关测试与是否注册过仓无关（只验鉴权与放行） */
    @BeforeAll
    fun warmup() {
        // 触发 context 即可；无仓也不影响鉴权语义
        assertEquals(200, req("GET", "/api/auth/status").statusCode())
    }

    @Test
    fun `status 报告启用且未认证`() {
        val r = req("GET", "/api/auth/status")
        assertEquals(200, r.statusCode(), r.body())
        val tree = json.readTree(r.body())
        assertTrue(tree.get("authEnabled").asBoolean(), "配置了 admin-token 应报告启用")
        assertEquals(false, tree.get("authenticated").asBoolean(), "未带 token 应为未认证")
    }

    @Test
    fun `未带 token 访问受保护端点返回 401`() {
        val r = req("GET", "/api/projects")
        assertEquals(401, r.statusCode(), r.body())
        // /actuator 放行（compose healthcheck 兼容）
        assertEquals(200, req("GET", "/actuator/health").statusCode())
    }

    @Test
    fun `login 正确令牌通过 错误令牌 401`() {
        // 正确的 token（与 admin-token 属性一致）
        val ok = req("POST", "/api/auth/login", body = """{"token":"test-admin-token-123"}""")
        assertEquals(200, ok.statusCode(), ok.body())
        assertTrue(json.readTree(ok.body()).get("authenticated").asBoolean())

        // 错误的 token
        val bad = req("POST", "/api/auth/login", body = """{"token":"wrong-token"}""")
        assertEquals(401, bad.statusCode(), bad.body())
    }

    @Test
    fun `带正确 token 可访问受保护端点并报告已认证`() {
        val projects = req("GET", "/api/projects", token = ADMIN)
        assertEquals(200, projects.statusCode(), projects.body())

        val status = req("GET", "/api/auth/status", token = ADMIN)
        assertTrue(json.readTree(status.body()).get("authenticated").asBoolean(), "带正确 token 应报告已认证")
    }

    // ── I4 RBAC ──────────────────────────────────────────────────────────

    private var seq = 0

    private fun createUser(adminToken: String, base: String, role: String): Triple<String, Long, String> {
        val username = "${base}_${System.nanoTime()}"
        val r = req("POST", "/api/auth/users", token = adminToken, body = """{"username":"$username","role":"$role"}""")
        assertEquals(200, r.statusCode(), r.body())
        val tree = json.readTree(r.body())
        return Triple(tree.at("/token").asText(), tree.at("/user/id").asLong(), username)
    }

    @Test
    fun `login 支持用户名+令牌 昵称 不匹配返回401`() {
        val (vtoken, _, vname) = createUser(ADMIN, "login_user", "viewer")

        val ok = req("POST", "/api/auth/login", body = """{"username":"$vname","token":"$vtoken"}""")
        assertEquals(200, ok.statusCode(), ok.body())
        assertEquals(vname, json.readTree(ok.body()).at("/username").asText())

        // 用户名与令牌不匹配 → 401
        val bad = req("POST", "/api/auth/login", body = """{"username":"someone_else","token":"$vtoken"}""")
        assertEquals(401, bad.statusCode(), bad.body())
    }

    @Test
    fun `成员管理分层 - viewer不可见 admin 可`() {
        val (vtoken, _, _) = createUser(ADMIN, "hier_viewer", "viewer")

        assertEquals(403, req("GET", "/api/auth/users", token = vtoken).statusCode(), "viewer 不可查看成员")
        assertEquals(403, req("POST", "/api/auth/users", token = vtoken, body = """{"username":"x","role":"viewer"}""").statusCode(), "viewer 不可创建")
        assertEquals(200, req("GET", "/api/auth/users", token = ADMIN).statusCode(), "admin 可管理成员")
    }

    @Test
    fun `写资源分层 - 注册仓库 viewer403 editor200 未登录401`() {
        val (vtoken, _, _) = createUser(ADMIN, "rbac_viewer", "viewer")
        val (etoken, _, _) = createUser(ADMIN, "rbac_editor", "editor")

        val url = """{"url":"file://$origin"}"""
        assertEquals(401, req("POST", "/api/repos", body = url).statusCode(), "未登录 401")
        assertEquals(403, req("POST", "/api/repos", token = vtoken, body = url).statusCode(), "viewer 403")
        assertEquals(200, req("POST", "/api/repos", token = etoken, body = url).statusCode(), "editor 200")
    }

    @Test
    fun `改角色与重置令牌与删除后令牌失效`() {
        val (t1, uid, _) = createUser(ADMIN, "mutable1", "viewer")

        // 改角色 editor
        val role = req("POST", "/api/auth/users/$uid/role", token = ADMIN, body = """{"role":"editor"}""")
        assertEquals(200, role.statusCode(), role.body())

        // 重置令牌：旧失效、新有效
        val reset = req("POST", "/api/auth/users/$uid/token", token = ADMIN)
        assertEquals(200, reset.statusCode(), reset.body())
        val t2 = json.readTree(reset.body()).at("/token").asText()
        assertTrue(t1 != t2, "新令牌应不同")

        assertEquals(401, req("GET", "/api/projects", token = t1).statusCode(), "旧令牌失效")
        assertEquals(200, req("GET", "/api/projects", token = t2).statusCode(), "新令牌有效")

        // 删除后全新令牌失效
        assertEquals(204, req("DELETE", "/api/auth/users/$uid", token = ADMIN).statusCode(), "删除返回 204")
        assertEquals(401, req("GET", "/api/projects", token = t2).statusCode(), "删除后令牌失效")
    }

    @Test
    fun `未启用鉴权 - 无 admin-token 时全放行`() {
        // AuthApiTest 配置启用了鉴权；此项验证在 WebApiTest（无 token 配置）里覆盖。
        assertTrue(json.readTree(req("GET", "/api/auth/status").body()).get("authEnabled").asBoolean())
    }
}