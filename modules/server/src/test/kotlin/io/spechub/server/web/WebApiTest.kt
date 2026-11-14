package io.spechub.server.web

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
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
 * E2 + E6 集成验收（对应 P0 验收）：POST /api/repos 注册本地仓 → 摄取返回 apiCount；
 * 查询 / webhook 触发；token 注入不落库。
 *
 * - 用 RANDOM_PORT + 真实 HTTP（JDK HttpClient），不依赖 MockMvc（Boot 4 / Spring 7 下
 *   spring-test 的 web 包在当前构建链解析异常，见 server/build.gradle.kts 注释）。
 * - seed 用独立根 `build/tmp/webtest-origin`，**绝不 touch `build/tmp/webtest`**（那是 KernelConfig
 *   bean 管理的 gitsource/sqlite/artifacts；删除它会连带删掉已建 sqlite，导致 CANTOPEN）。
 * - receptionize 多次运行幂等：同 url 注册返回同一 repo，apiCount 稳定。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.git-source-dir=build/tmp/webtest/gitsource",
        "spechub.sqlite-dir=build/tmp/webtest/sqlite",
        "spechub.artifacts-dir=build/tmp/webtest/artifacts",
        "spechub.polling-interval-ms=3600000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebApiTest {

    @LocalServerPort
    var port: Int = 0

    companion object {
        private val json = JsonMapper.builder().build()
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/webtest-origin")
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
                components:
                  schemas:
                    Pet: { type: object, properties: { name: { type: string } } }
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

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun findOriginRepoId(): Long {
        val repos = json.readTree(get("/api/repos").body())
        val found = repos.firstOrNull { it.get("url").asString() == origin }
            ?: throw AssertionError("origin 仓未注册：$repos")
        return found.get("id").asLong()
    }

    private fun put(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    @Order(6)
    fun `写回 spec - PUT 平台提交并摄取`() {
        val repoId = findOriginRepoId()

        // 读当前 spec（工作树）
        val read = get("/api/repos/$repoId/spec?path=specs%2Fpet%2Fopenapi.yaml")
        assertEquals(200, read.statusCode(), read.body())

        // 写回：追加一个 DELETE operation 的新版本文档
        val newSpec = """
            openapi: 3.1.0
            info: { title: Pet Store, version: "1.1.0" }
            paths:
              /pets:
                get:
                  operationId: listPets
                  responses:
                    "200": { description: ok }
              /pets/{petId}:
                delete:
                  operationId: removePet
                  parameters: [{ name: petId, in: path, required: true, schema: { type: integer } }]
                  responses:
                    "204": { description: removed }
            components:
              schemas:
                Pet: { type: object, properties: { name: { type: string } } }
        """.trimIndent()
        val body = """{"path":"specs/pet/openapi.yaml","content":"${newSpec.replace("\n", "\\n").replace("\"", "\\\"")}"}"""
        val resp = put("/api/repos/$repoId/spec", body)
        assertEquals(200, resp.statusCode(), resp.body())
        val r = json.readTree(resp.body())
        assertEquals("specs/pet/openapi.yaml", r.at("/path").asString())
        assertTrue(r.at("/commit").asString().length >= 40, "应有完整 commit hash")

        // 写回后摄取 → 投影含 removePet
        val apis = json.readTree(get("/api/repos/$repoId/apis").body())
        val ops = buildList { for (a in apis) { add(a.at("/operationId").asString()) } }
        assertTrue("removePet" in ops, "写回后应能查到 removePet：$ops")
    }

    @Test
    @Order(1)
    fun `注册仓并即摄取 - 返回 apiCount`() {
        val resp = post("/api/repos", """{"url":"$origin","branch":"main"}""")
        assertEquals(200, resp.statusCode(), resp.body())
        val body = json.readTree(resp.body())
        assertEquals(origin, body.at("/repo/url").asString())
        assertEquals(1, body.at("/apiCount").asInt())
        assertEquals(1, body.at("/schemaCount").asInt())
    }

    @Test
    @Order(2)
    fun `repo 列表与接口查询`() {
        val repoId = findOriginRepoId()

        val apis = json.readTree(get("/api/repos/$repoId/apis").body())
        assertEquals("GET", apis[0].get("method").asString())
        assertEquals("/pets", apis[0].get("path").asString())
        assertEquals("listPets", apis[0].get("operationId").asString())

        val found = json.readTree(get("/api/apis?q=listPets").body())
        assertEquals("listPets", found[0].get("operationId").asString())
    }

    @Test
    @Order(3)
    fun `webhook 触发增量摄取`() {
        val repoId = findOriginRepoId()
        val resp = post("/api/webhooks/$repoId", "")
        assertEquals(200, resp.statusCode(), resp.body())
        assertEquals(1, json.readTree(resp.body()).at("/apiCount").asInt())
    }

    @Test
    @Order(4)
    fun `token 注入但不落库`() {
        val resp = post("/api/repos", """{"url":"$origin","token":"secret-token-abc"}""")
        assertEquals(200, resp.statusCode(), resp.body())
        assertEquals(1, json.readTree(resp.body()).at("/apiCount").asInt())

        val repos = json.readTree(get("/api/repos").body())
        val urls = buildList { for (r in repos) { add(r.get("url").asString()) } }
        assertTrue(urls.none { it.contains("secret-token-abc") }, "token 不应出现在任何 repo.url：$urls")
        assertTrue(urls.any { it == origin }, "origin 仓应存在且 URL 干净：$urls")
    }

    @Test
    @Order(5)
    fun `注册非法仓返回 5xx 且不崩`() {
        val resp = post("/api/repos", """{"url":"/nonexistent/path/repo.git"}""")
        assertTrue(resp.statusCode() >= 500, "非法仓应服 5xx：${resp.statusCode()}")
    }
}