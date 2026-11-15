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
 * J —— 导入导出（无鉴权放行，聚焦链路）：
 * - J1/J2 导入：POST /api/import/swagger2 + /postman（带 repoId+path）→ 写回真源 + 摄取；
 * - J3 导出：GET /repos/{id}/export/openapi31|openapi30|postman。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
    ],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportExportApiTest : TestDbIsolation() {

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
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
        private val json = JsonMapper.builder().build()
        private lateinit var origin: String

        @JvmStatic
        @BeforeAll
        fun seedOrigin() {
            val root = Path.of("build/tmp/importtest-origin")
            if (Files.exists(root)) root.toFile().deleteRecursively()
            val bare = root.resolve("origin").also { Files.createDirectories(it) }
            val cli = GitCli()
            val git = GitStore()
            cli.run(bare, listOf("init", "--bare", "-b", "main")).require("init bare")
            val work = root.resolve("seedwork")
            Files.createDirectories(work.resolve("specs/pet"))
            Files.writeString(work.resolve("specs/pet/openapi.yaml"), SEED_SPEC)
            cli.run(work, listOf("init", "-b", "main")).require("init")
            git.stage(work, listOf(".")).require("stage")
            git.commit(work, "seed", Identity("tester", "t@spechub.test")).require("commit")
            cli.run(work, listOf("remote", "add", "origin", bare.toAbsolutePath().toString())).require("add remote")
            cli.run(work, listOf("push", "origin", "main")).require("push")
            origin = bare.toAbsolutePath().toString()
        }

        private val SEED_SPEC = """
            openapi: 3.1.0
            info: { title: Seed Store, version: "1.0.0" }
            paths:
              /pets:
                get:
                  operationId: listPets
                  responses:
                    "200": { description: ok, content: { application/json: { schema: { type: array, items: { ${'$'}ref: "#/components/schemas/Pet" } } } } }
            components:
              schemas:
                Pet: { type: object, properties: { id: { type: integer }, name: { type: string } } }
        """.trimIndent()
    }

    private val client = HttpClient.newHttpClient()

    private fun req(method: String, path: String, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .method(method, HttpRequest.BodyPublishers.ofString(body ?: ""))
        if (body != null) b.header("Content-Type", "application/json")
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
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

    private fun seedAndRegister(): Long {
        val reg = req("POST", "/api/repos", """{"url":"file://$origin"}""")
        return json.readTree(reg.body()).at("/repo/id").asLong()
    }

    // ── 导入 ──────────────────────────────────────────────────────────────

    @Test
    fun `J2 导入 Swagger-2-0 写回真源并摄取`() {
        val repoId = seedAndRegister()
        val swagger = """
            {"swagger":"2.0","info":{"title":"Old API","version":"1"},"host":"old.example.com","basePath":"/api",
             "paths":{"/legacy":{"get":{"operationId":"legacyList","responses":{"200":{"description":"ok","schema":{"type":"array","items":{"type":"string"}}}}}}},
             "definitions":{}}
        """.trimIndent()
        val r = req("POST", "/api/import/swagger2",
            """{"content":${json.writeValueAsString(swagger)},"repoId":$repoId,"path":"specs/import/old-api.yaml"}""")
        assertEquals(200, r.statusCode(), r.body())
        val t = json.readTree(r.body())
        assertEquals("Old API", t.at("/title").asText())
        assertEquals(1, t.at("/apiCount").asInt())
        assertTrue(t.at("/written/repoId").asLong() == repoId, "写回信息带 repoId")
        assertTrue(t.at("/specYaml").asText().contains("3.1"), "预览 YAML 已归一化为 3.1")

        // 真源确实有该文件（写回 commit+push）
        val spec = req("GET", "/api/repos/$repoId/spec?path=specs/import/old-api.yaml")
        assertEquals(200, spec.statusCode(), "导入文件可读")
        assertTrue(spec.body().contains("legacy"))

        // 摄取后索引可见导入的接口（归一化管道闭环）
        val apis = json.readTree(req("GET", "/api/apis").body())
        assertTrue(apis.toString().contains("/legacy"), "导入接口进索引：$apis")
    }

    @Test
    fun `J1 导入 Postman 写回真源`() {
        val repoId = seedAndRegister()
        val pm = """
            {"info":{"name":"PMS"},"item":[
              {"name":"list","request":{"method":"GET","url":{"path":["pets"],"query":[{"key":"limit","value":"10"}]}}},
              {"name":"create","request":{"method":"POST","url":{"path":["pets"]},"body":{"mode":"raw","raw":"{}"}}}
            ]}
        """.trimIndent()
        val r = req("POST", "/api/import/postman",
            """{"content":${json.writeValueAsString(pm)},"repoId":$repoId,"path":"specs/import/pms.yaml"}""")
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(2, json.readTree(r.body()).at("/apiCount").asInt())
        val spec = req("GET", "/api/repos/$repoId/spec?path=specs/import/pms.yaml")
        assertEquals(200, spec.statusCode())
        assertTrue(spec.body().contains("/pets"))
    }

    // ── 导出 ──────────────────────────────────────────────────────────────

    @Test
    fun `J3 导出 openapi31 与 openapi30 降级 postman`() {
        val repoId = seedAndRegister()

        val yaml31 = req("GET", "/api/repos/$repoId/export/openapi31")
        assertEquals(200, yaml31.statusCode())
        assertTrue(yaml31.body().contains("3.1"), "openapi 3.1 export")
        assertTrue(yaml31.body().contains("listPets"))
        assertTrue(yaml31.body().contains("Pet"), "components.schemas.Pet 导出")

        val yaml30 = req("GET", "/api/repos/$repoId/export/openapi30")
        assertEquals(200, yaml30.statusCode())
        assertTrue(yaml30.body().contains("3.0.3"), "降级到 3.0.3")

        // 3.0 降级可被 3.0 解析确认（无 3.1-only 关键字段）
        val pm = req("GET", "/api/repos/$repoId/export/postman")
        assertEquals(200, pm.statusCode())
        val pmTree = json.readTree(pm.body())
        assertTrue(!pmTree.at("/info/name").asText().isBlank(), "collection name（repo 名）")
        assertTrue(pmTree.at("/item").size() >= 1, "item 数量")
        assertTrue(pmTree.toString().contains("listPets"), "Postman 含请求")
    }

    @Test
    fun `J4 导出 markdown 与 html 门户`() {
        val repoId = seedAndRegister()

        val md = req("GET", "/api/repos/$repoId/export/markdown")
        assertEquals(200, md.statusCode())
        assertTrue(md.body().contains("## /pets"), "path 锚")
        assertTrue(md.body().contains("listPets"), "接口摘要")
        assertTrue(md.body().contains("**响应**"), "响应章节")

        val html = req("GET", "/api/repos/$repoId/export/html")
        assertEquals(200, html.statusCode())
        assertTrue(html.body().contains("<!doctype html>"), "HTML doctype")
        assertTrue(html.body().contains("<details class=\"api"), "接口折叠块")
        assertTrue(html.body().contains("function filter()"), "内嵌搜索 JS（离线可用）")
        assertTrue(!html.body().contains("<script src"), "无外部脚本依赖")
    }

    @Test
    fun `J4 docx pdf 软依赖 - pandoc 缺失友好报错`() {
        val repoId = seedAndRegister()
        val pandoc = try {
            ProcessBuilder("sh", "-c", "command -v pandoc").start().let { it.waitFor() == 0 }
        } catch (e: Exception) {
            false
        }
        val status = req("POST", "/api/repos/$repoId/document/pdf").statusCode()
        if (pandoc) {
            assertEquals(200, status, "装了 pandoc 时应生成 pdf")
        } else {
            assertTrue(status == 503, "pandoc 未装应 503（软依赖提示），实际 $status")
        }
    }

    @Test
    fun `导出未知 format 400 且 stage 参数生效`() {
        val repoId = seedAndRegister()
        assertEquals(400, req("GET", "/api/repos/$repoId/export/nope").statusCode())
        assertEquals(200, req("GET", "/api/repos/$repoId/export/openapi31?stage=release").statusCode(),
            "release 空快照也可导出（空 paths 不报错）")
    }
}