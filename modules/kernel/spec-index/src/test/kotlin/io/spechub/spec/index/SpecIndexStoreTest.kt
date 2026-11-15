package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** E4：SQLite 表 + 唯一键 + 幂等重建 + api↔schema 引用可解析 */
class SpecIndexStoreTest {

    @TempDir
    lateinit var temp: Path

    private fun newStore(): SpecIndexStore {
        val db = IndexDb(temp.resolve("index-test.db"))
        db.migrate()
        return SpecIndexStore(db)
    }

    @Test
    fun `repo 注册幂等 - 同 URL 不重复建行`() {
        val store = newStore()
        val a = store.insertRepo("default", "https://example.com/org/repo.git")
        val b = store.insertRepo("default", "https://example.com/org/repo.git")
        assertEquals(a.id, b.id, "同 URL 幂等")
        assertEquals(1, store.listRepos().size)
    }

    @Test
    fun `version 以 specHash 幂等`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        val v1 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        val v2 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        assertEquals(v1.id, v2.id, "同 hash 幂等")
        val vNew = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-b")
        assertTrue(vNew.id != v1.id, "内容变了 → 新版本行")
        assertEquals(2, store.listVersions(repo.id).size)
    }

    @Test
    fun `replaceApis 全量替换对应可重建投影`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        val v = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "h1")
        store.replaceApisForRepo(repo.id, listOf(
            ApiRecord(repoId = repo.id, versionId = v.id, method = "GET", path = "/pets", operationId = "listPets", tag = "pets", summary = "list", specJson = "{}"),
            ApiRecord(repoId = repo.id, versionId = v.id, method = "POST", path = "/pets", operationId = "createPet", tag = "pets", specJson = "{}"),
        ))
        assertEquals(2, store.countApis(repo.id))

        // 增量重扫：换了一批（删旧 / 增新）→ 全量替换，不残留
        store.replaceApisForRepo(repo.id, listOf(
            ApiRecord(repoId = repo.id, versionId = v.id, method = "GET", path = "/pets", operationId = "listPets", tag = "pets", specJson = "{}"),
            ApiRecord(repoId = repo.id, versionId = v.id, method = "GET", path = "/pets/{id}", operationId = "getPet", specJson = "{}"),
        ))
        assertEquals(2, store.countApis(repo.id))
        assertEquals(setOf("/pets", "/pets/{id}"), store.listApis(repo.id).map { it.path }.toSet(), "无残留旧 path")
    }

    @Test
    fun `api 查询支持按 method tag 关键字过滤`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        store.replaceApisForRepo(repo.id, listOf(
            ApiRecord(repoId = repo.id, method = "GET", path = "/pets", operationId = "listPets", summary = "list all pets", specJson = "{}"),
            ApiRecord(repoId = repo.id, method = "POST", path = "/pets", operationId = "createPet", summary = "create pet", specJson = "{}"),
        ))
        assertEquals(1, store.listApis(repo.id, method = "GET").size)
        assertEquals(1, store.listApis(repo.id, keyword = "create").size)
        assertEquals(1, store.listAllApis(keyword = "list").size)
    }

    @Test
    fun `schema 投影以 repo+name 幂等且可取回`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        store.replaceSchemasForRepo(repo.id, listOf(
            SchemaRecord(repoId = repo.id, name = "Pet", specJson = """{"type":["object"]}"""),
            SchemaRecord(repoId = repo.id, name = "Owner", specJson = """{"type":["object"]}"""),
        ))
        val pet = store.getSchema(repo.id, "Pet")
        assertNotNull(pet)
        assertEquals("""{"type":["object"]}""", pet.specJson)
    }

    @Test
    fun `级联删除 - 删 repo 清空投影`() {
        val db = IndexDb(temp.resolve("cascade.db"))
        db.migrate()
        val store = SpecIndexStore(db)
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        store.replaceApisForRepo(repo.id, listOf(ApiRecord(repoId = repo.id, method = "GET", path = "/pets", specJson = "{}")))
        assertEquals(1, store.countApis(repo.id))

        db.execute("DELETE FROM repo WHERE id=?", repo.id)
        assertEquals(0, store.countApis(repo.id), "ON DELETE CASCADE 应清空 api 投影")
    }

    // ── I6 双层版本：draft 演进 / release 快照冻结 ─────────────────────────────

    @Test
    fun `promotePath 把最新 draft 冻结为 release 快照`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        val v1 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        store.replaceApisForRepo(repo.id, listOf(ApiRecord(repoId = repo.id, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"old":true}""")))
        store.replaceSchemasForRepo(repo.id, listOf(SchemaRecord(repoId = repo.id, versionId = v1.id, name = "Pet", specJson = """{"old":true}""")))

        val promoted = store.promotePath(repo.id, "specs/pet/openapi.yaml")
        assertNotNull(promoted)
        assertEquals(Stage.RELEASE, promoted.stage)
        assertEquals("hash-a", promoted.specHash)

        // release 视图 = 发布时刻快照
        assertEquals(1, store.listApis(repo.id, stage = Stage.RELEASE).size)
        assertEquals("""{"old":true}""", store.listApis(repo.id, stage = Stage.RELEASE).single().specJson)
        assertEquals(1, store.listSchemas(repo.id, stage = Stage.RELEASE).size)
        // 同一 path 其它版本应全降 draft
        assertEquals(0, store.listVersionsOfPath(repo.id, "specs/pet/openapi.yaml").count { it.stage == Stage.DRAFT })
    }

    @Test
    fun `release 快照不随 draft 后续演进漂移（消费者默认读 release）`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        val v1 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        store.replaceApisForRepo(repo.id, listOf(ApiRecord(repoId = repo.id, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"v":1}""")))
        store.promotePath(repo.id, "specs/pet/openapi.yaml")

        // 新摄取（draft 演进）：新 hash → 新 version + 只重建 draft 档
        val v2 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-b")
        store.replaceApisForRepo(repo.id, listOf(ApiRecord(repoId = repo.id, versionId = v2.id, method = "GET", path = "/pets", specJson = """{"v":2}""")))
        assertEquals("""{"v":2}""", store.listApis(repo.id, stage = Stage.DRAFT).single().specJson, "draft 已演进")
        assertEquals("""{"v":1}""", store.listApis(repo.id, stage = Stage.RELEASE).single().specJson, "release 快照冻结不被覆盖")
        assertEquals(2, store.countApis(repo.id), "draft + release 双投影共存（同 method+path 不同 stage）")
    }

    @Test
    fun `同 hash 幂等续跑不破坏已发布 release`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        val v1 = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        store.replaceApisForRepo(repo.id, listOf(ApiRecord(repoId = repo.id, versionId = v1.id, method = "GET", path = "/pets", specJson = """{"v":1}""")))
        store.promotePath(repo.id, "specs/pet/openapi.yaml")

        // 幂等续跑（内容未变）：upsertVersion 复用既有行，stage 保持 release 不降级
        val again = store.upsertVersion(repo.id, "specs/pet/openapi.yaml", "hash-a")
        assertEquals(v1.id, again.id)
        assertEquals(Stage.RELEASE, again.stage, "已发布的版本幂等续跑仍为 release")
    }

    @Test
    fun `promote 无版本时返回 null`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        assertEquals(null, store.promotePath(repo.id, "specs/none.yaml"))
    }

    // ── changelog 与 mock_rule 桩 ──────────────────────────────────────────────

    @Test
    fun `changelog 追加与按 repo 逆序查询`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git")
        store.addChangelog(repo.id, null, "spechub-bot", ChangelogAction.SPEC_WRITE, "write a")
        store.addChangelog(repo.id, null, "spechub-bot", ChangelogAction.PROMOTE, "release b")
        val logs = store.listChangelogs(repo.id, null, limit = 10)
        assertEquals(2, logs.size)
        assertEquals(ChangelogAction.PROMOTE, logs.first().action, "逆序：最新在前")
        assertEquals("spechub-bot", logs.first().actor)
    }

    @Test
    fun `mock rule 幂等 upsert 与删除`() {
        val store = newStore()
        val repo = store.insertRepo("default", "https://example.com/o/r.git", "main")
        val r1 = store.upsertMockRule(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "A"))
        val r2 = store.upsertMockRule(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "B"))
        assertEquals(r1.id, r2.id, "同（api,pattern,type）幂等 upsert")
        assertEquals("B", store.listMockRules(repo.id).single().ruleValue, "更新值")
        store.deleteMockRule(checkNotNull(r1.id) { "mock rule upsert 应回填 id" })
        assertEquals(0, store.listMockRules(repo.id).size)
    }
}