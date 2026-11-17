package io.spechub.server.web

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.IndexDb
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I7 —— 变更订阅 e2e（无鉴权模式）：
 * 订阅 /pets → 改 spec push → webhook 摄取 → subscription 的 team 收到 notification；
 * 幂等续跑（同内容再次摄取）不重复通知。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionApiTest : TestDbIsolation() {
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
            TestManagementDb.register(registry, "t_subscription")
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }

        private val json = JsonMapper.builder().build()
        private lateinit var origin: String
        private lateinit var seedWork: Path

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/subtest-origin")
            if (Files.exists(root)) root.toFile().deleteRecursively()
            val bare = root.resolve("origin").also { Files.createDirectories(it) }
            val cli = GitCli()
            val git = GitStore()
            cli.run(bare, listOf("init", "--bare", "-b", "main")).require("init bare")
            seedWork = root.resolve("seedwork")
            Files.createDirectories(seedWork.resolve("specs/pet"))
            Files.writeString(seedWork.resolve("specs/pet/openapi.yaml"), SPEC_V1)
            cli.run(seedWork, listOf("init", "-b", "main")).require("init")
            git.stage(seedWork, listOf(".")).require("stage")
            git.commit(seedWork, "seed", Identity("tester", "t@spechub.test")).require("commit")
            cli.run(seedWork, listOf("remote", "add", "origin", bare.toAbsolutePath().toString())).require("add remote")
            cli.run(seedWork, listOf("push", "origin", "main")).require("push")
            origin = bare.toAbsolutePath().toString()
        }

        private val SPEC_V1 =
            """
            openapi: 3.1.0
            info: { title: Pet Store, version: "1.0.0" }
            paths:
              /pets:
                get:
                  operationId: listPets
                  responses:
                    "200": { description: ok }
            """.trimIndent()

        private val SPEC_V2 = SPEC_V1.replace("operationId: listPets", "operationId: listPetsV2")
    }

    private val client = HttpClient.newHttpClient()

    private var rev = 0

    /** 把 seedWork 的 spec 改为递增 version 并 push（模拟生产者结构更新；SpecHash 忽略注释故改语义字段） */
    private fun pushSpecV2() {
        rev++
        val git = GitStore()
        Files.writeString(
            seedWork.resolve("specs/pet/openapi.yaml"),
            SPEC_V1.replace("1.0.0", "1.0.$rev"),
        )
        git.stage(seedWork, listOf("specs/pet/openapi.yaml")).require("stage")
        git.commit(seedWork, "update spec", Identity("tester", "t@spechub.test")).require("commit")
        GitCli().run(seedWork, listOf("push", "origin", "main")).require("push")
    }

    private fun req(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val b =
            HttpRequest
                .newBuilder(URI("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) b.header("Content-Type", "application/json")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = req("GET", path)

    /** 管理库 JdbcTemplate（subscription/notification 已迁管理库，按 schema 隔离清理） */
    @Autowired
    lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @BeforeEach
    fun cleanProjection() {
        clearManagementTables(jdbc)
        db.execute("DELETE FROM version")
        db.execute("DELETE FROM schema")
        db.execute("DELETE FROM api")
    }

    /** 手动辅助：注册 origin 并返回 repoId */
    private fun seedAndRegister(): Long {
        val reg = req("POST", "/api/repos", """{"url":"file://$origin"}""")
        return json.readTree(reg.body()).at("/repo/id").asLong()
    }

    @Test
    fun `订阅改动触发通知 且 幂等续跑不重复`() {
        val repoId = seedAndRegister()

        // 订阅 /pets by pay-team
        val sub =
            req(
                "POST",
                "/api/subscriptions",
                """{"repoId":$repoId,"method":"GET","path":"/pets","team":"pay-team","notifyUrl":"https://pay.example/hook"}""",
            )
        assertEquals(200, sub.statusCode(), sub.body())
        assertEquals("/pets", json.readTree(sub.body()).at("/path").asText())
        // 同 team 再订一遍 → 幂等（仍是 1 条）
        req("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets","team":"pay-team"}""")
        assertEquals(1, json.readTree(get("/api/subscriptions?repoId=$repoId").body()).size())

        // 无变化时通知为空
        assertTrue(json.readTree(get("/api/subscriptions/notifications?team=pay-team").body()).size() == 0)

        // 生产者改 spec push → webhook 摄取 → 通知 1 条
        pushSpecV2()
        assertEquals(200, req("POST", "/api/webhooks/$repoId").statusCode())
        var notifs = json.readTree(get("/api/subscriptions/notifications?team=pay-team").body())
        assertEquals(1, notifs.size(), "结构变更应落 1 条通知")
        assertTrue(
            notifs
                .get(0)
                ?.at("/summary")
                ?.asText()
                ?.contains("结构变更") == true,
            "summary 带结构变更",
        )
        assertTrue(notifs.get(0)?.at("/method")?.asText() == "GET")
        assertTrue(notifs.get(0)?.at("/path")?.asText() == "/pets")

        // 幂等续跑：不改内容再次 webhook → 不新增通知
        assertEquals(200, req("POST", "/api/webhooks/$repoId").statusCode())
        notifs = json.readTree(get("/api/subscriptions/notifications?team=pay-team").body())
        assertEquals(1, notifs.size(), "同内容再次摄取不重复通知")
    }

    @Test
    fun `取消订阅后不再通知`() {
        val repoId = seedAndRegister()
        val sub = req("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets","team":"pay-team"}""")
        val subId = json.readTree(sub.body()).at("/id").asLong()

        assertEquals(204, req("DELETE", "/api/subscriptions/$subId").statusCode())
        pushSpecV2()
        req("POST", "/api/webhooks/$repoId")
        assertTrue(
            json.readTree(get("/api/subscriptions/notifications?team=pay-team").body()).size() == 0,
            "订阅取消后结构变更不再通知",
        )
    }
}
