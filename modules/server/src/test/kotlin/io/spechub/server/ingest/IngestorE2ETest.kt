package io.spechub.server.ingest

import io.spechub.server.config.SpecHubProperties
import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SyncStatus
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

    private val specFixture =
        """
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

    private fun props(): SpecHubProperties =
        SpecHubProperties(
            gitSourceDir = temp.resolve("gitsource").toString(),
            sqliteDir = temp.resolve("sqlite").toString(),
            artifactsDir = temp.resolve("artifacts").toString(),
        )

    @Test
    fun `fetch 小仓摄取 → api 与 schema 入投影，幂等续跑 version 不重复`() {
        val origin = seedOrigin()
        val db = IndexDb(temp.resolve("idx.db")).also { it.migrate() }
        val projection = ProjectionStore(db)
        val ingestor = Ingestor(git, projection, IngestHooks(), props())

        val repo = RepoRecord(1, 1, origin.toString(), "main", SyncStatus.NEW)
        val result = ingestor.ingest(repo)
        assertEquals(2, result.apiCount, "应提取 GET /pets 与 DELETE /pets/{id}")
        assertEquals(1, result.schemaCount, "components.schemas 应入投影")
        assertTrue(result.messages.isEmpty(), "不应有警告/跳过：${result.messages}")

        val apis = projection.listApis(repo.id)
        assertEquals(setOf("GET", "DELETE"), apis.map { it.method }.toSet())
        assertEquals("listPets", apis.first { it.method == "GET" }.operationId)
        assertNotNull(projection.getSchema(repo.id, "Pet"))

        // 幂等续跑：内容未变 → version 与 api 都不重复
        ingestor.ingest(repo)
        assertEquals(1, projection.listVersions(repo.id).size, "同内容不应新增版本")
        assertEquals(2, projection.countApis(repo.id))
    }

    @Test
    fun `上游 push 新文档 → 增量摄取更新投影且保留新版本`() {
        val origin = seedOrigin()
        val db = IndexDb(temp.resolve("idx2.db")).also { it.migrate() }
        val projection = ProjectionStore(db)
        val ingestor = Ingestor(git, projection, IngestHooks(), props())
        val repo = RepoRecord(1, 1, origin.toString(), "main", SyncStatus.NEW)

        ingestor.ingest(repo)
        assertEquals(2, projection.countApis(repo.id), "首接应有 2 个 api")

        // 上游：工作仓改文档（增加一个 operation）并 push
        val seedWork = temp.resolve("seedwork2")
        cloneSeedAndEdit(origin, seedWork, specFixture.replace("operationId: listPets", "operationId: listPetsV2"))

        ingestor.ingest(repo)
        assertEquals(2, projection.countApis(repo.id), "仍是 2 个 operation（内容变化不翻倍）")
        assertEquals(2, projection.listVersions(repo.id).size, "内容变了 → 新版本")
        assertTrue(projection.listApis(repo.id).all { it.operationId != null })
    }

    /**
     * 选型文档 §11 验收标准：管理库与投影库可独立重置——
     * 清空投影后从 git 全量重扫，仍由 PlatformStore 承载的管理数据（changelog）一条不丢。
     * 边界：mock_rule.api_id 仅为展示快照（非 FK）；用户/成员、repo、订阅/通知、评论已迁管理库。
     */
    @Test
    fun `清空投影后从 git 重扫 - 管理数据不丢失`() {
        val origin = seedOriginWithDocs()
        val db = IndexDb(temp.resolve("idx-rebuild.db")).also { it.migrate() }
        val projection = ProjectionStore(db)
        val ingestor = Ingestor(git, projection, IngestHooks(), props())
        val repo = RepoRecord(1, 1, origin.toString(), "main", SyncStatus.NEW)
        ingestor.ingest(repo)

        // 管理数据已全部迁管理库（Phase 2），本测试只验证投影可重建
        val api = projection.listApis(repo.id).first { it.operationId == "listPets" }
        val doc = projection.listDocs(repo.id).first { it.path == "docs/pet/handover.md" }

        clearProjection(db)

        // 从 git 全量重扫（投影重建的唯一输入是真源 + 管理库的 repo 列表）
        val result = ingestor.ingest(repo)
        assertEquals(2, result.apiCount, "投影应从 git 重建")
        assertEquals(1, projection.listDocs(repo.id).size, "doc 投影应从 git 重建")
    }

    /** Phase 1 同库过渡：显式清空投影表。Phase 2 物理拆分后改为删除投影库文件。 */
    private fun clearProjection(db: IndexDb) {
        if (db.fts5Available) db.execute("DELETE FROM doc_fts")
        db.execute("DELETE FROM doc")
        db.execute("DELETE FROM api")
        db.execute("DELETE FROM schema")
        db.execute("DELETE FROM version")
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

    private fun seedOriginWithDocs(): Path {
        val origin = temp.resolve("origin-docs").also { Files.createDirectories(it) }
        cli.run(origin, listOf("init", "--bare", "-b", "main")).require("init bare")

        val work = temp.resolve("seedwork-docs")
        Files.createDirectories(work.resolve("specs/pet"))
        Files.createDirectories(work.resolve("docs/pet"))
        Files.writeString(work.resolve("specs/pet/openapi.yaml"), specFixture)
        Files.writeString(work.resolve("docs/pet/handover.md"), "# 接手分析\n\n服务器处理器负责订单处理与库存扣减。")
        cli.run(work, listOf("init", "-b", "main")).require("init")
        git.stage(work, listOf(".")).require("stage")
        git.commit(work, "seed spec and doc", Identity("tester", "t@spechub.test")).require("commit")
        cli.run(work, listOf("remote", "add", "origin", origin.toString())).require("add remote")
        cli.run(work, listOf("push", "origin", "main")).require("push")
        return origin
    }

    private fun cloneSeedAndEdit(
        origin: Path,
        work: Path,
        newContent: String,
    ) {
        git.cloneInto(origin.toString(), work).require("clone")
        Files.writeString(work.resolve("specs/pet/openapi.yaml"), newContent)
        git.stage(work, listOf("specs/pet/openapi.yaml")).require("stage edit")
        git.commit(work, "edit spec", Identity("tester", "t@spechub.test")).require("commit edit")
        git.push(work, "origin", "main").require("push edit")
    }
}
