package io.spechub.server.web

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.IndexDb
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
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
 * I8 —— 评审审批流（启用鉴权：「写操作皆审」§6.4 决策 3）：
 * - 启鉴权下 `PUT /repos/{id}/spec` 不直接落真源，先建 pending 评审（真源 HEAD 不变）。
 * - requester 不能批自己的提交（403）；他人（editor）approve → 真源提交新 commit + 摄取。
 * - reject 仅置状态；已处理再批 409。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=review-admin-token",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewApiTest : TestDbIsolation() {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var db: IndexDb

    companion object {

        /** 每次运行全新临时投影目录（companion 静态字段即时创建，不依赖注入时机） */
        private val TEMPDIR: java.nio.file.Path = kotlin.io.path.createTempDirectory("spechub-test-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            TestManagementDb.register(registry, "t_review")
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
        private val json = JsonMapper.builder().build()
        private const val ADMIN = "review-admin-token"
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/reviewtest-origin")
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

    private fun head(rev: String = "HEAD"): String {
        val r = GitCli().run(Path.of(origin), listOf("rev-parse", rev))
        return if (r.success) r.stdout.trim() else ""
    }

    private var seq = 0

    /** 建一个 editor 用户，返回 token 和用户名 */
    private fun createEditor(prefix: String): Pair<String, String> {
        seq++
        val name = "${prefix}_${System.nanoTime()}"
        val r = req("POST", "/api/auth/users", token = ADMIN, body = """{"username":"$name","role":"editor"}""")
        assertEquals(200, r.statusCode(), r.body())
        return json.readTree(r.body()).at("/token").asText() to name
    }

    @BeforeEach
    fun cleanProjection() {
        db.execute("DELETE FROM review")
        db.execute("DELETE FROM notification")
        db.execute("DELETE FROM subscription")
        db.execute("DELETE FROM changelog")
        db.execute("DELETE FROM mock_rule")
        db.execute("DELETE FROM version")
        db.execute("DELETE FROM schema")
        db.execute("DELETE FROM api")
        db.execute("DELETE FROM repo")
    }

    @Test
    fun `启鉴权写回走评审 - 自批403 他人批准落真源 重复批409`() {
        val (reqToken, reqName) = createEditor("requester")
        val (appToken, _) = createEditor("approver")

        // admin 注册仓库（editor 注册也是一样作用域）
        val reg = req("POST", "/api/repos", token = ADMIN, body = """{"url":"file://$origin"}""")
        val repoId = json.readTree(reg.body()).at("/repo/id").asLong()

        val before = head()
        val put = req("PUT", "/api/repos/$repoId/spec", token = reqToken,
            body = """{"path":"specs/pet/openapi.yaml","content":"openapi: 3.1.0\ninfo: { title: Pet Store v2, version: \"1.0.0\" }\npaths: {}\n"}""")
        assertEquals(200, put.statusCode(), put.body())
        val putTree = json.readTree(put.body())
        val reviewId = putTree.at("/reviewId").asLong()
        assertTrue(reviewId > 0, "返回 reviewId")
        assertEquals("pending", putTree.at("/status").asText())
        assertEquals(before, head(), "评审通过前真源 HEAD 不变（未落真源）")

        // 提交人自批 → 403（需他人评审）
        assertEquals(403, req("POST", "/api/reviews/$reviewId/approve", token = reqToken).statusCode(), "不能批自己的提交")

        // 未登录审批 → 401（filter 拦 /api/reviews/*？reviews 不在放行名单 → 需 token）
        assertEquals(401, req("POST", "/api/reviews/$reviewId/approve").statusCode())

        // 他人 approve → 真源新 commit
        val ok = req("POST", "/api/reviews/$reviewId/approve", token = appToken)
        assertEquals(200, ok.statusCode(), ok.body())
        assertTrue(before != head(), "真源 HEAD 前进（新 commit）")
        // 摄取后 specHash 更新（draft 视图读取新内容）
        val detail = req("GET", "/api/reviews/$reviewId", token = appToken)
        assertEquals("approved", json.readTree(detail.body()).at("/status").asText())

        // 已处理再批 → 409
        assertEquals(409, req("POST", "/api/reviews/$reviewId/approve", token = appToken).statusCode())

        // pending 列表为空
        assertTrue(json.readTree(req("GET", "/api/reviews?status=pending", token = appToken).body()).size() == 0)
        assertEquals(reqName, json.readTree(req("GET", "/api/reviews?status=approved", token = appToken).body()).get(0)?.at("/requester")?.asText(), "approved 列表带提交人")
    }

    @Test
    fun `reject 打回且真源不动`() {
        val (reqToken, _) = createEditor("requester2")
        val (appToken, _) = createEditor("approver2")
        val reg = req("POST", "/api/repos", token = ADMIN, body = """{"url":"file://$origin"}""")
        val repoId = json.readTree(reg.body()).at("/repo/id").asLong()

        val before = head()
        val put = req("PUT", "/api/repos/$repoId/spec", token = reqToken,
            body = """{"path":"specs/pet/openapi.yaml","content":"broken content"}""")
        val reviewId = json.readTree(put.body()).at("/reviewId").asLong()

        assertEquals(204, req("POST", "/api/reviews/$reviewId/reject", token = appToken,
            body = """{"reason":"缺 required"}""").statusCode())
        val detail = req("GET", "/api/reviews/$reviewId", token = appToken)
        assertEquals("rejected", json.readTree(detail.body()).at("/status").asText())
        assertEquals("缺 required", json.readTree(detail.body()).at("/rejectReason").asText())
        assertEquals(before, head(), "打回后真源 HEAD 不变")
    }
}