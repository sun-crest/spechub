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

/**
 * I5 —— 轻评论 + 变更记录（无鉴权模式：未配置 admin-token 全放行，聚焦业务链路）：
 * - 接口评论：发表 / 列表 / 删除（作者可删）。
 * - spec 写回在 changelog 带 diff 摘要；全局变更流 GET /api/changelog 报告所有写操作。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommentApiTest : TestDbIsolation() {

    @LocalServerPort
    var port: Int = 0

    /** 投影清理（§6.4 决策 7 可重建纪律：测试前清业务表保证幂等，跨运行无残留污染） */
    @Autowired
    lateinit var db: IndexDb

    @BeforeEach
    fun cleanProjection() {
        db.execute("DELETE FROM comment")
        db.execute("DELETE FROM changelog")
        db.execute("DELETE FROM mock_rule")
        db.execute("DELETE FROM version")
        db.execute("DELETE FROM schema")
        db.execute("DELETE FROM api")
        db.execute("DELETE FROM repo")
    }

    companion object {

        /** 每次运行全新临时投影目录（companion 静态字段即时创建，不依赖注入时机） */
        private val TEMPDIR: java.nio.file.Path = kotlin.io.path.createTempDirectory("spechub-test-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            TestManagementDb.register(registry, "t_comment")
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
        private val json = JsonMapper.builder().build()
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/commenttest-origin")
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

    private fun req(method: String, path: String, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) b.header("Content-Type", "application/json")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = req("GET", path)

    /** ArrayNode（tools.jackson 3）的简单查找：找第一个指定字段值等于 value 的子节点 */
    private fun tools.jackson.databind.JsonNode?.findFirst(field: String, value: String): tools.jackson.databind.JsonNode? {
        val arr = this ?: return null
        for (i in 0 until arr.size()) {
            val n = arr.get(i)
            if (n?.get(field)?.asText() == value) return n
        }
        return null
    }

    private fun ensureRepoAndApi(): Pair<Long, Long> {
        val repos = json.readTree(get("/api/repos").body())
        val repoId = if (repos.size() == 0) {
            json.readTree(req("POST", "/api/repos", """{"url":"file://$origin"}""").body()).at("/repo/id").asLong()
        } else {
            repos.get(0)?.at("/id")?.asLong() ?: throw AssertionError("无 repo")
        }
        val apisBody = get("/api/apis").body()
        val apis = json.readTree(apisBody)
        val api = apis.findFirst("path", "/pets") ?: throw AssertionError("无 /pets api; apis=$apisBody")
        return repoId to (api.at("/id").asLong())
    }

    @Test
    fun `评论全链路 - 发表 列表 删除`() {
        val (repoId, apiId) = ensureRepoAndApi()

        assertEquals(200, req("POST", "/api/apis/$apiId/comments", """{"body":"这个字段需要说明"}""").statusCode())
        assertEquals(200, req("POST", "/api/apis/$apiId/comments", """{"body":"收到，下版补"}""").statusCode())

        val list = json.readTree(get("/api/apis/$apiId/comments").body())
        assertEquals(2, list.size(), "两条评论")
        assertEquals("这个字段需要说明", list.get(0)?.at("/body")?.asText(), "评论正序")
        assertEquals("anonymous", list.get(0)?.at("/author")?.asText(), "未启用鉴权作者兜底 anonymous")
        assertEquals(repoId, list.get(0)?.at("/repoId")?.asLong(), "repoId 回显")

        val cid = list.get(0)?.at("/id")?.asLong() ?: throw AssertionError("无 comment id")
        assertEquals(204, req("DELETE", "/api/comments/$cid").statusCode())
        assertEquals(1, json.readTree(get("/api/apis/$apiId/comments").body()).size(), "删除后剩 1 条")

        // 评论也算写操作，进 changelog
        val log = json.readTree(get("/api/repos/$repoId/changelog").body())
        assertTrue(log.toString().contains("\"action\":\"comment\""), "changelog 含 comment 动作")
    }

    private fun assertTrue(cond: Boolean, msg: String) {
        if (!cond) throw AssertionError(msg)
    }

    @Test
    fun `spec 写回带 diff 摘要 且全局变更流可查`() {
        val (repoId, _) = ensureRepoAndApi()
        val spec = get("/api/repos/$repoId/spec?path=specs/pet/openapi.yaml").body()

        // 第一次写回（无 HEAD^ → diff 为空），第二次写回应有 diff 摘要
        val v1 = spec.replace("title: Pet Store", "title: Pet Store beta")
        assertEquals(200, req("PUT", "/api/repos/$repoId/spec",
            json.writeValueAsString(mapOf("path" to "specs/pet/openapi.yaml", "content" to v1))).statusCode())
        val v2 = spec.replace("title: Pet Store", "title: Pet Store v2")
        assertEquals(200, req("PUT", "/api/repos/$repoId/spec",
            json.writeValueAsString(mapOf("path" to "specs/pet/openapi.yaml", "content" to v2))).statusCode())

        // 最新一条 spec.write 的 detail 应含 diff 摘要（+1/-1 specs/...）
        val log = json.readTree(get("/api/repos/$repoId/changelog").body())
        assertTrue(log.get(0)?.at("/action")?.asText() == "spec.write", "最新一条为 spec.write：$log")
        assertTrue(log.get(0)?.at("/detail")?.asText()?.contains("diff +") == true, "detail 含 diff 摘要：${log.get(0)}")

        // 全局变更流（跨 repo）应包含该写操作
        val global = json.readTree(get("/api/changelog?limit=50").body())
        assertTrue(global.size() > 0 && global.toString().contains("spec.write"), "全局变更流含 spec.write")
    }
}