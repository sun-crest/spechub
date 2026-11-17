package io.spechub.server.web

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
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
 * K —— AI 文档层（Doc 域）集成（无鉴权放行）：
 * - docs/ Markdown 随摄取入索引（listDocs）；全文检索 q（K2 中文子串）；
 * - 详情 markdown 含 front-matter related 生成的「关联接口」节（K3 Doc↔Api 链接）；评论挂在 doc。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocApiTest : TestDbIsolation() {
    @LocalServerPort
    var port: Int = 0

    companion object {
        private val json = JsonMapper.builder().build()
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/doctest-origin")
            if (Files.exists(root)) root.toFile().deleteRecursively()
            val bare = root.resolve("origin").also { Files.createDirectories(it) }
            val cli = GitCli()
            val git = GitStore()
            cli.run(bare, listOf("init", "--bare", "-b", "main")).require("init bare")
            val work = root.resolve("seedwork")
            Files.createDirectories(work.resolve("specs/pet"))
            Files.createDirectories(work.resolve("docs/petstore"))
            Files.writeString(work.resolve("specs/pet/openapi.yaml"), SPEC)
            Files.writeString(work.resolve("docs/petstore/handover.md"), HANDOVER)
            cli.run(work, listOf("init", "-b", "main")).require("init")
            git.stage(work, listOf(".")).require("stage")
            git.commit(work, "seed", Identity("tester", "t@spechub.test")).require("commit")
            cli.run(work, listOf("remote", "add", "origin", bare.toAbsolutePath().toString())).require("add remote")
            cli.run(work, listOf("push", "origin", "main")).require("push")
            origin = bare.toAbsolutePath().toString()
        }

        private val SPEC =
            """
            openapi: 3.1.0
            info: { title: Pet Store, version: "1.0.0" }
            paths:
              /pets:
                get:
                  operationId: listPets
                  responses: { "200": { description: ok } }
            """.trimIndent()

        private val HANDOVER =
            """
            ---
            title: 宠物店接手分析
            related: [/pets]
            ---
            # 宠物店接手分析

            后台处理器负责订单处理与库存扣减，接入方联调见 GET /pets。
            """.trimIndent()
    }

    private val client = HttpClient.newHttpClient()

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

    @Test
    fun `docs 摄取入索引 - 列表与全文检索`() {
        req("POST", "/api/repos", """{"url":"file://$origin"}""")

        val docs = json.readTree(req("GET", "/api/docs").body())
        assertTrue(docs.size() >= 1, "docs/ Markdown 已摄取")
        val doc = docs.firstOrNull { it.at("/path").asText().startsWith("docs/") } ?: throw AssertionError("无 doc：$docs")
        assertEquals("宠物店接手分析", doc.at("/title").asText(), "front-matter title 生效")

        // K2 全文检索（中文子串）
        val hit = json.readTree(req("GET", "/api/docs?q=处理器").body())
        assertTrue(hit.size() >= 1, "检索「处理器」命中")

        val miss = json.readTree(req("GET", "/api/docs?q=完全无关词").body())
        assertTrue(miss.size() == 0, "无关词不命中")
    }

    @Test
    fun `doc 详情含关联接口节 且 doc 可评论`() {
        req("POST", "/api/repos", """{"url":"file://$origin"}""")
        val docId =
            json
                .readTree(req("GET", "/api/docs").body())
                .firstOrNull()
                ?.at("/id")
                ?.asLong()
                ?: throw AssertionError("无 doc")

        val detail = json.readTree(req("GET", "/api/docs/$docId").body())
        assertTrue(detail.at("/markdown").asText().contains("关联接口"), "front-matter related → 关联接口节")
        // 正文保留 front-matter 剥离后的内容（不再以 --- 开头）
        assertTrue(!detail.at("/markdown").asText().startsWith("---"), "front-matter 已剥离到正文之外")

        // doc 评论（doc_id 寻址）
        assertEquals(200, req("POST", "/api/docs/$docId/comments", """{"body":"这里补充一下鉴权"}""").statusCode())
        val comments = json.readTree(req("GET", "/api/docs/$docId/comments").body())
        assertEquals(1, comments.size())
        assertEquals("这里补充一下鉴权", comments.get(0)?.at("/body")?.asText())
    }
}
