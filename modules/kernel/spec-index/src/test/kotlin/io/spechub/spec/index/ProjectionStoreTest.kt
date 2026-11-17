package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 投影域：version（发布快照）/ api / schema / doc 的可重建语义与幂等。 */
class ProjectionStoreTest {
    @TempDir
    lateinit var temp: Path

    private fun projection(): ProjectionStore {
        val db = IndexDb(temp.resolve("projection-test.db"))
        db.migrate()
        return ProjectionStore(db)
    }

    @Test
    fun `version 以 specHash 幂等`() {
        val projection = projection()
        val repoId = 1L
        val v1 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        val v2 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        assertEquals(v1.id, v2.id, "同 hash 幂等")
        val vNew = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-b")
        assertTrue(vNew.id != v1.id, "内容变了 → 新版本行")
        assertEquals(2, projection.listVersions(repoId).size)
    }

    @Test
    fun `replaceApis 全量替换对应可重建投影`() {
        val projection = projection()
        val repoId = 1L
        val v = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "h1")
        projection.replaceApisForRepo(
            repoId,
            listOf(
                ApiRecord(repoId = repoId, versionId = v.id, method = "GET", path = "/pets", operationId = "listPets", tag = "pets", summary = "list", specJson = "{}"),
                ApiRecord(repoId = repoId, versionId = v.id, method = "POST", path = "/pets", operationId = "createPet", tag = "pets", specJson = "{}"),
            ),
        )
        assertEquals(2, projection.countApis(repoId))

        // 增量重扫：换了一批（删旧 / 增新）→ 全量替换，不残留
        projection.replaceApisForRepo(
            repoId,
            listOf(
                ApiRecord(repoId = repoId, versionId = v.id, method = "GET", path = "/pets", operationId = "listPets", tag = "pets", specJson = "{}"),
                ApiRecord(repoId = repoId, versionId = v.id, method = "GET", path = "/pets/{id}", operationId = "getPet", specJson = "{}"),
            ),
        )
        assertEquals(2, projection.countApis(repoId))
        assertEquals(setOf("/pets", "/pets/{id}"), projection.listApis(repoId).map { it.path }.toSet(), "无残留旧 path")
    }

    @Test
    fun `api 查询支持按 method tag 关键字过滤`() {
        val projection = projection()
        val repoId = 1L
        projection.replaceApisForRepo(
            repoId,
            listOf(
                ApiRecord(repoId = repoId, method = "GET", path = "/pets", operationId = "listPets", summary = "list all pets", specJson = "{}"),
                ApiRecord(repoId = repoId, method = "POST", path = "/pets", operationId = "createPet", summary = "create pet", specJson = "{}"),
            ),
        )
        assertEquals(1, projection.listApis(repoId, method = "GET").size)
        assertEquals(1, projection.listApis(repoId, keyword = "create").size)
        assertEquals(1, projection.listAllApis(keyword = "list").size)
    }

    @Test
    fun `schema 投影以 repo+name 幂等且可取回`() {
        val projection = projection()
        val repoId = 1L
        projection.replaceSchemasForRepo(
            repoId,
            listOf(
                SchemaRecord(repoId = repoId, name = "Pet", specJson = """{"type":["object"]}"""),
                SchemaRecord(repoId = repoId, name = "Owner", specJson = """{"type":["object"]}"""),
            ),
        )
        val pet = projection.getSchema(repoId, "Pet")
        assertNotNull(pet)
        assertEquals("""{"type":["object"]}""", pet.specJson)
    }

    // ── I6 双层版本：draft 演进 / release 快照冻结 ─────────────────────────────

    @Test
    fun `promotePath 把最新 draft 冻结为 release 快照`() {
        val projection = projection()
        val repoId = 1L
        val v1 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        projection.replaceApisForRepo(repoId, listOf(ApiRecord(repoId = repoId, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"old":true}""")))
        projection.replaceSchemasForRepo(repoId, listOf(SchemaRecord(repoId = repoId, versionId = v1.id, name = "Pet", specJson = """{"old":true}""")))

        val promoted = projection.promotePath(repoId, "specs/pet/openapi.yaml")
        assertNotNull(promoted)
        assertEquals(Stage.RELEASE, promoted.stage)
        assertEquals("hash-a", promoted.specHash)

        // release 视图 = 发布时刻快照
        assertEquals(1, projection.listApis(repoId, stage = Stage.RELEASE).size)
        assertEquals("""{"old":true}""", projection.listApis(repoId, stage = Stage.RELEASE).single().specJson)
        assertEquals(1, projection.listSchemas(repoId, stage = Stage.RELEASE).size)
        // 同一 path 其它版本应全降 draft
        assertEquals(0, projection.listVersionsOfPath(repoId, "specs/pet/openapi.yaml").count { it.stage == Stage.DRAFT })
    }

    @Test
    fun `release 快照不随 draft 后续演进漂移（消费者默认读 release）`() {
        val projection = projection()
        val repoId = 1L
        val v1 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        projection.replaceApisForRepo(repoId, listOf(ApiRecord(repoId = repoId, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"v":1}""")))
        projection.promotePath(repoId, "specs/pet/openapi.yaml")

        // 新摄取（draft 演进）：新 hash → 新 version + 只重建 draft 档
        val v2 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-b")
        projection.replaceApisForRepo(repoId, listOf(ApiRecord(repoId = repoId, versionId = v2.id, method = "GET", path = "/pets", specJson = """{"v":2}""")))
        assertEquals("""{"v":2}""", projection.listApis(repoId, stage = Stage.DRAFT).single().specJson, "draft 已演进")
        assertEquals("""{"v":1}""", projection.listApis(repoId, stage = Stage.RELEASE).single().specJson, "release 快照冻结不被覆盖")
        assertEquals(2, projection.countApis(repoId), "draft + release 双投影共存（同 method+path 不同 stage）")
    }

    @Test
    fun `同 hash 幂等续跑不破坏已发布 release`() {
        val projection = projection()
        val repoId = 1L
        val v1 = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        projection.replaceApisForRepo(repoId, listOf(ApiRecord(repoId = repoId, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"v":1}""")))
        projection.promotePath(repoId, "specs/pet/openapi.yaml")

        // 幂等续跑（内容未变）：upsertVersion 复用既有行，stage 保持 release 不降级
        val again = projection.upsertVersion(repoId, "specs/pet/openapi.yaml", "hash-a")
        assertEquals(v1.id, again.id)
        assertEquals(Stage.RELEASE, again.stage, "已发布的版本幂等续跑仍为 release")
    }

    @Test
    fun `promote 无版本时返回 null`() {
        val projection = projection()
        val repoId = 1L
        assertEquals(null, projection.promotePath(repoId, "specs/none.yaml"))
    }

    // ── Doc 域：摄取投影 + 全文检索（中文子串）────────────────────────

    @Test
    fun `doc 摄取与全文检索 中文子串命中`() {
        val projection = projection()
        val repoId = 1L
        projection.replaceDocsForRepo(
            repoId,
            listOf(
                DocRecord(repoId = repoId, path = "docs/petstore/handover.md", title = "接手分析", markdown = "# 接手分析\n\n服务器处理器负责订单处理与库存扣减。"),
                DocRecord(repoId = repoId, path = "docs/petstore/requirements.md", title = "需求说明", markdown = "# 需求\n\n支持宠物目录检索。"),
            ),
        )

        assertEquals(2, projection.listDocs(repoId).size)
        val doc = projection.listDocs().first()
        assertEquals("接手分析", doc.title)
        assertEquals("docs/petstore/handover.md", doc.path)
        assertNotNull(projection.getDoc(doc.id!!))

        // 中文子串检索（LIKE 兜底保证；FTS5 环境支持时亦可命中）
        assertTrue(projection.searchDocs("处理器").isNotEmpty(), "中文子串应命中")
        assertTrue(projection.searchDocs("扣减").isNotEmpty(), "正文子串命中")
        assertTrue(projection.searchDocs("完全无关的词").isEmpty(), "无关词不命中")
    }
}
