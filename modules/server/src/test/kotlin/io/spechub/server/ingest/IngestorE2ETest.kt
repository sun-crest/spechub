package io.spechub.server.ingest

import io.spechub.spec.gstore.GitCli
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E3 端到端主线测试：本地 git 仓（file://）→ Ingestor 摄取 → SQLite 索引可查询，一次封闭验证。
 * 复刻 P0 验收链路的即测版；不用 Spring 容器，直接装配模块依赖（kernel 纯库易测）。
 */
class IngestorE2ETest {

    companion object {
        private val cli = GitCli()
        private lateinit var remote: Path
        private lateinit var gitsource: Path
        private lateinit var dbFile: Path

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val temp = Files.createTempDirectory("spechub-e2e")
            remote = temp.resolve("billing.git")
            gitsource = temp.resolve("gitsource")
            dbFile = temp.resolve("index.db")
            Files.createDirectories(gitsource)

            // 生产端小仓：一个 openapi.yaml
            val seed = temp.resolve("seed")
            Files.createDirectories(seed.resolve("spec"))
            seed.resolve("spec/openapi.yaml").writeText("""
                openapi: 3.1.0
                info:
                  title: billing service
                  version: 1.0.0
                paths:
                  /invoices:
                    get:
                      operationId: listInvoices
                      summary: 发票列表
                      tags: [billing]
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  ${'$'}ref: '#/components/schemas/Invoice'
                  /invoices/{id}:
                    get:
                      operationId: getInvoice
                      summary: 发票详情
                      responses:
                        '200':
                          description: ok
                components:
                  schemas:
                    Invoice:
                      type: object
                      properties:
                        id: { type: string }
                        amount: { type: number, format: double }
            """.trimIndent())
            cli.check(temp, listOf("init", "--bare", "-b", "main", remote.toString()))
            cli.check(temp, listOf("init", "-b", "main", seed.toString()))
            cli.check(seed, listOf("add", "."))
            cli.check(seed, listOf("-c", "user.name=seed", "-c", "user.email=seed@localhost", "commit", "-m", "init billing"))
            cli.check(seed, listOf("remote", "add", "origin", remote.toString()))
            cli.check(seed, listOf("push", "-u", "origin", "HEAD"))
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
        }
    }

    @Test
    fun `ingest remote repo then query normalized apis from sqlite`() {
        val props = io.spechub.server.config.SpecHubProperties(gitsourcePath = gitsource.toString(), dbPath = dbFile.toString())
        val gitStore = io.spechub.spec.gstore.GitStore(gitsource)
        val db = io.spechub.spec.index.IndexDb.open(dbFile)
        val store = io.spechub.spec.index.SpecIndexStore(db)
        val ingestor = Ingestor(props, gitStore, store)

        val result = try {
            ingestor.ingest(
                project = "finance",
                org = "acme",
                name = "billing",
                remoteUrl = remote.toUri().toString(),
                source = "cli",
            )
        } finally {
            db.close()
        }

        assertFalse(result.unchanged, "首次摄取应生效")
        assertEquals("spec/openapi.yaml", result.specFile)

        // 重新打开索引查询（验证持久化）
        io.spechub.spec.index.IndexDb.open(dbFile).use { db2 ->
            val store2 = io.spechub.spec.index.SpecIndexStore(db2)
            val apis = store2.listApis("finance", "acme", "billing")
            assertTrue(apis.isNotEmpty(), "索引应含 api 记录")
            assertEquals(2, apis.size)
            val listInvoices = apis.first { it.operationId == "listInvoices" }
            assertEquals("/invoices", listInvoices.path)
            assertEquals("GET", listInvoices.method)
            assertTrue(listInvoices.tags.contains("billing"))

            val schemas = store2.listSchemas("finance", "acme", "billing")
            assertEquals(1, schemas.size)
            assertEquals("Invoice", schemas[0].name)

            val versions = store2.versionHistory("finance", "acme", "billing")
            assertEquals(1, versions.size)
            assertEquals("cli", versions[0].source)
            assertTrue(versions[0].commitSha!!.matches(Regex("[0-9a-f]{40}")), "应记录镜像仓 origin/HEAD SHA")
        }
    }

    @Test
    fun `same push re-ingest is idempotent`() {
        val props = io.spechub.server.config.SpecHubProperties(gitsourcePath = gitsource.toString(), dbPath = dbFile.toString())
        val gitStore = io.spechub.spec.gstore.GitStore(gitsource)
        val db = io.spechub.spec.index.IndexDb.open(dbFile)
        val store = io.spechub.spec.index.SpecIndexStore(db)
        val ingestor = Ingestor(props, gitStore, store)
        try {
            ingestor.ingest(project = "finance", org = "acme", name = "billing",
                remoteUrl = remote.toUri().toString(), source = "webhook")
            db.close()
            val db2 = io.spechub.spec.index.IndexDb.open(dbFile)
            val store2 = io.spechub.spec.index.SpecIndexStore(db2)
            assertEquals(1, store2.versionHistory("finance", "acme", "billing").size,
                "同 hash 再触发不应重复记 version")
            db2.close()
        } catch (e: Exception) {
            throw e
        }
    }
}