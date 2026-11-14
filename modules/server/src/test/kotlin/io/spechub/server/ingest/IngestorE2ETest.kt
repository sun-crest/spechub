package io.spechub.server.ingest

import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.SyncStatus
import io.spechub.server.config.SpecHubProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E3 端到端：真 git 仓（file://）→ 摄取器（mirror → parser → 索引）→ 断言 api/schema 投影 + 幂等续跑。
 * 不启动 Spring，手动装配 kernel 组件（对应 KernelConfig）。
 */
class IngestorE2ETest {

    @TempDir
    lateinit var temp: Path

    private val cli = GitCli()
    private val git = GitStore()

    private val specFixture = """
        openapi: 3.1.0
        info: { title: Pet Store, version: "1.0.0" }
        paths:
          /pets:
            get:
              operationId: listPets
              tags: [pets]
              responses:
                "200":
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: "#/components/schemas/Pet"
          /pets/{id}:
            delete:
              operationId: removePet
              parameters:
                - { name: id, in: path, required: true, schema: { type: integer } }
              responses:
                "204": { description: removed }
        components:
          schemas:
            Pet:
              type: object
              properties:
                id: { type: [integer, "null"] }
                name: { type: string }
    """.trimIndent()

    private fun props(): SpecHubProperties = SpecHubProperties(
        gitSourceDir = temp.resolve("gitsource").toString(),
        sqliteDir = temp.resolve("sqlite").toString(),
        artifactsDir = temp.resolve("artifacts").toString(),
    )

    @Test
    fun `fetch 小仓摄取 → api 与 schema 入投影，幂等续跑 version 不重复`() {
        val origin = seedOrigin()
        val index = SpecIndexStore(IndexDb(temp.resolve("idx.db")).also { it.migrate() })
        val ingestor = Ingestor(git, index, props())

        val repo = index.insertRepo("default", origin.toString(), "main")
        val result = ingestor.ingest(repo)
        assertEquals(2, result.apiCount, "应提取 GET /pets 与 DELETE /pets/{id}")
        assertEquals(1, result.schemaCount, "components.schemas 应入投影")
        assertTrue(result.messages.isEmpty(), "不应有警告/跳过：${result.messages}")
        assertEquals(SyncStatus.OK, index.getRepo(repo.id)!!.syncStatus)

        val apis = index.listApis(repo.id)
        assertEquals(setOf("GET", "DELETE"), apis.map { it.method }.toSet())
        assertEquals("listPets", apis.first { it.method == "GET" }.operationId)
        assertNotNull(index.getSchema(repo.id, "Pet"))

        // 幂等续跑：内容未变 → version 与 api 都不重复
        ingestor.ingest(repo)
        assertEquals(1, index.listVersions(repo.id).size, "同内容不应新增版本")
        assertEquals(2, index.countApis(repo.id))
    }

    @Test
    fun `上游 push 新文档 → 增量摄取更新投影且保留新版本`() {
        val origin = seedOrigin()
        val index = SpecIndexStore(IndexDb(temp.resolve("idx2.db")).also { it.migrate() })
        val ingestor = Ingestor(git, index, props())
        val repo = index.insertRepo("default", origin.toString(), "main")

        ingestor.ingest(repo)
        assertEquals(2, index.countApis(repo.id), "首接应有 2 个 api")

        // 上游：工作仓改文档（增加一个 operation）并 push
        val seedWork = temp.resolve("seedwork2")
        cloneSeedAndEdit(origin, seedWork, specFixture.replace("operationId: listPets", "operationId: listPetsV2"))

        ingestor.ingest(repo)
        assertEquals(2, index.countApis(repo.id), "仍是 2 个 operation（内容变化不翻倍）")
        assertEquals(2, index.listVersions(repo.id).size, "内容变了 → 新版本")
        assertTrue(index.listApis(repo.id).all { it.operationId != null })
    }

    // ── 前置 git 用具 ──────────────────────────────────────────────────────

    private fun seedOrigin(): Path {
        val origin = temp.resolve("origin").also { Files.createDirectories(it) }
        cli.run(origin, listOf("init", "--bare", "-b", "main")).require("init bare")

        val work = temp.resolve("seedwork")
        Files.createDirectories(work.resolve("specs/pet"))
        Files.writeString(work.resolve("specs/pet/openapi.yaml"), specFixture)
        cli.run(work, listOf("init", "-b", "main")).require("init")
        git.stage(work, listOf(".")).require("stage")
        git.commit(work, "seed spec", Identity("tester", "t@spechub.test")).require("commit")
        cli.run(work, listOf("remote", "add", "origin", origin.toString())).require("add remote")
        cli.run(work, listOf("push", "origin", "main")).require("push")
        return origin
    }

    private fun cloneSeedAndEdit(origin: Path, work: Path, newContent: String) {
        git.cloneInto(origin.toString(), work).require("clone")
        Files.writeString(work.resolve("specs/pet/openapi.yaml"), newContent)
        git.stage(work, listOf("specs/pet/openapi.yaml")).require("stage edit")
        git.commit(work, "edit spec", Identity("tester", "t@spechub.test")).require("commit edit")
        git.push(work, "origin", "main").require("push edit")
    }
}