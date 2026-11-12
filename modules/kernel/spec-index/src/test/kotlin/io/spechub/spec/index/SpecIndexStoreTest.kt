package io.spechub.spec.index

import io.spechub.spec.parser.SpecNormalizer
import io.spechub.spec.parser.ParseResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecIndexStoreTest {

    private fun normalizeNow(raw: String) = SpecNormalizer.normalize(raw) as ParseResult.Success

    private val sample = """
        openapi: 3.1.0
        info:
          title: 微服务 A
          version: 1.0.0
        paths:
          /users:
            get:
              operationId: listUsers
              summary: 列出用户
              tags: [user]
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema:
                        type: array
                        items:
                          ${'$'}ref: '#/components/schemas/User'
          /users/{id}:
            get:
              operationId: getUser
              summary: 用户详情
              responses:
                '200':
                  description: ok
        components:
          schemas:
            User:
              type: object
              properties:
                id:
                  type: string
                name:
                  type: [string, "null"]
    """.trimIndent()

    @Test
    fun `ingest snapshot then list apis and schemas`() {
        IndexDb.openInMemory().use { db ->
            val store = SpecIndexStore(db)
            val doc = normalizeNow(sample).doc

            val r = store.ingestSnapshot(
                project = "demo", org = "acme", name = "user-api",
                remoteUrl = "https://gitea/acme/user-api.git", branch = "main",
                specFile = "spec/openapi.yaml", specHash = "abc123", specVersion = "3.1.0",
                apis = IndexExtractor.apis(doc, repoId = 1, specHash = "abc123"),
                schemas = IndexExtractor.schemas(doc, repoId = 1, specHash = "abc123"),
                commitSha = "c1", source = "cli", message = "initial",
            )
            assertFalse(r.unchanged)

            // 需要真实 repo_id：ensureRepo 内部已建 repo，查 listRepos 拿 id
            val repo = store.listRepos().first().repo
            val apis = store.listApis("demo", "acme", "user-api")
            assertEquals(2, apis.size, "应有 2 条 API")
            assertEquals(repo.id, apis[0].repoId)

            val schemas = store.listSchemas("demo", "acme", "user-api")
            assertEquals(1, schemas.size, "应有 1 个 schema")
            assertTrue(schemas[0].definition.contains("\"name\"") || schemas[0].definition.contains("name"),
                "schema 定义应含序列化的 schema: ${schemas[0].definition}")

            val history = store.versionHistory("demo", "acme", "user-api")
            assertEquals(1, history.size)
            assertEquals("c1", history[0].commitSha)
        }
    }

    @Test
    fun `same hash is idempotent short path`() {
        IndexDb.openInMemory().use { db ->
            val store = SpecIndexStore(db)
            val doc = normalizeNow(sample).doc

            val first = store.ingestSnapshot(
                project = "p", org = "o", name = "r",
                remoteUrl = "u", branch = "main",
                specFile = "spec.yaml", specHash = "h1", specVersion = "3.1.0",
                apis = IndexExtractor.apis(doc, 1, "h1"), schemas = IndexExtractor.schemas(doc, 1, "h1"),
                commitSha = "c1", source = "cli", message = null,
            )
            val second = store.ingestSnapshot(
                project = "p", org = "o", name = "r",
                remoteUrl = "u", branch = "main",
                specFile = "spec.yaml", specHash = "h1", specVersion = "3.1.0",
                apis = IndexExtractor.apis(doc, 1, "h1"), schemas = IndexExtractor.schemas(doc, 1, "h1"),
                commitSha = "c1", source = "webhook", message = null,
            )
            assertFalse(first.unchanged)
            assertTrue(second.unchanged, "同 hash 应幂等短路（不重复记 version）")
            assertEquals(1, store.versionHistory("p", "o", "r").size, "version 只记一条")
        }
    }

    @Test
    fun `changed hash replaces projection`() {
        IndexDb.openInMemory().use { db ->
            val store = SpecIndexStore(db)
            val doc = normalizeNow(sample).doc

            store.ingestSnapshot(
                project = "p", org = "o", name = "r",
                remoteUrl = "u", branch = "main",
                specFile = "spec.yaml", specHash = "h1", specVersion = "3.1.0",
                apis = IndexExtractor.apis(doc, 1, "h1"), schemas = IndexExtractor.schemas(doc, 1, "h1"),
                commitSha = "c1", source = "cli", message = "v1",
            )
            // 模拟接口新增：加一个 path
            val doc2 = normalizeNow("""
                openapi: 3.1.0
                info: { title: t, version: "2" }
                paths:
                  /users:
                    get: { operationId: listUsers, responses: { '200': { description: ok } } }
                  /health:
                    get: { operationId: health, responses: { '200': { description: ok } } }
                components:
                  schemas: {}
            """.trimIndent()).doc
            store.ingestSnapshot(
                project = "p", org = "o", name = "r",
                remoteUrl = "u", branch = "main",
                specFile = "spec.yaml", specHash = "h2", specVersion = "3.1.0",
                apis = IndexExtractor.apis(doc2, 1, "h2"), schemas = IndexExtractor.schemas(doc2, 1, "h2"),
                commitSha = "c2", source = "webhook", message = "v2",
            )
            val apis = store.listApis("p", "o", "r")
            assertEquals(2, apis.size)
            assertTrue(apis.all { it.specHash == "h2" }, "旧投影应被整体替换")
            assertEquals(2, store.versionHistory("p", "o", "r").size, "两条版本历史")
        }
    }
}