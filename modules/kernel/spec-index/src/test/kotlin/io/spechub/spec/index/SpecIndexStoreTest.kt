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
}