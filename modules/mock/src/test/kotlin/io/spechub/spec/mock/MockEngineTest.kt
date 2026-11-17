package io.spechub.spec.mock

import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.MockRuleRecord
import io.spechub.spec.index.SchemaRecord
import io.spechub.spec.index.PlatformStore
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Stage
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.io.SpecHash
import io.spechub.spec.model.io.SpecModelIO
import io.spechub.spec.parser.ParseResult
import io.spechub.spec.parser.SpecParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import tools.jackson.databind.JsonNode

class MockEngineTest {

    private fun setup(): Triple<MockEngine, PlatformStore, Long> {
        val dir = createTempDirectory("spechub-mock-test")
        val db = IndexDb(dir.resolve("index.db"))
        db.migrate()
        val projection = ProjectionStore(db)
        val platform = PlatformStore(db)
        val repo = platform.insertRepo("default", "/fixtures/mockstore")
        val yaml = checkNotNull(javaClass.classLoader.getResource("mockstore/openapi.yaml")).readText()
        val doc = (SpecParser.parse(yaml) as ParseResult.Success).doc
        val ver = projection.upsertVersion(repo.id, "specs/mockstore/openapi.yaml", SpecHash.of(doc))
        projection.replaceApisForRepo(repo.id, extractApis(repo.id, ver.id, doc))
        projection.replaceSchemasForRepo(repo.id, extractSchemas(repo.id, ver.id, doc))
        return Triple(MockEngine(projection, platform), platform, repo.id)
    }

    private fun extractApis(repoId: Long, versionId: Long, doc: OpenApiDoc): List<ApiRecord> =
        doc.paths.flatMap { (path, item) ->
            item.operations().map { (method, op) ->
                ApiRecord(
                    repoId = repoId, versionId = versionId, method = method.uppercase(), path = path,
                    operationId = op.operationId, tag = op.tags.firstOrNull(), summary = op.summary,
                    specJson = SpecModelIO.valueToTree(op).toString(),
                )
            }
        }

    private fun extractSchemas(repoId: Long, versionId: Long, doc: OpenApiDoc): List<SchemaRecord> =
        doc.components?.schemas?.map { (name, schema) ->
            SchemaRecord(repoId = repoId, versionId = versionId, name = name, specJson = SpecModelIO.valueToTree(schema).toString())
        } ?: emptyList()

    private fun bodyText(result: MockEngine.MockResult, field: String): String? =
        SpecModelIO.parseToTree(result.response.body).path(field)?.asText()

    @Test
    fun `schema 生成：$ref 与 format 样例、enum、array、object 嵌套`() {
        val (engine, _, repoId) = setup()
        val result = engine.generate(repoId, "GET", "/users/abc")
        assertNotNull(result, "应匹配 /users/{id}")
        assertEquals(200, result.response.status)
        assertEquals(Stage.DRAFT, result.stage, "未发布时回退 draft")
        assertEquals("user@example.com", bodyText(result, "email"))
        assertEquals("9f1d7e3a-2c44-4b8e-8f6a-1a2b3c4d5e6f", bodyText(result, "id"), "\$ref→User.format=uuid 生成固定 uuid 样例")
        assertEquals("admin", bodyText(result, "role"), "enum 应取首个")
        assertTrue(SpecModelIO.parseToTree(result.response.body).path("tags")?.isArray == true, "array 应生成数组")
        val addr = SpecModelIO.parseToTree(result.response.body).path("address")
        assertEquals("city", addr?.path("city")?.asText(), "纯 string 字段用字段名兜底")
        assertEquals(0, addr?.path("code")?.asInt(), "integer 默认 0")
    }

    @Test
    fun `path 模板匹配：精确命中与 {param} 段展开`() {
        val (engine, _, repoId) = setup()
        assertNotNull(engine.generate(repoId, "get", "/users/some-user-id"), "方法不区分大小写 + 模板段匹配")
        assertNotNull(engine.generate(repoId, "POST", "/users"), "精确路径命中")
        assertNull(engine.generate(repoId, "GET", "/nope"), "不存在的路径应返回 null")
        assertNull(engine.generate(repoId, "DELETE", "/users"), "方法不匹配应为 null")
    }

    @Test
    fun `responses 选择：具体码优先于 default，default 归一为 200`() {
        val (engine, _, repoId) = setup()
        assertEquals(201, engine.generate(repoId, "POST", "/users")!!.response.status, "应优先取精确码 201 而非 default")
        val status = engine.generate(repoId, "GET", "/status")!!
        assertEquals(200, status.response.status, "default → 200")
        assertTrue(status.response.body.contains("\"ok\"") && status.response.body.contains("true"), "boolean 默认 true")
        assertEquals(0, SpecModelIO.parseToTree(status.response.body).path("latency")?.asInt(), "integer 类型默认 0")
    }

    @Test
    fun `规则三档：value 覆盖字段、pattern 格式样例、script 求值`() {
        val (engine, platform, repoId) = setup()
        platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = "address.city", ruleType = RuleType.VALUE, ruleValue = "上海"))
        platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = ".*email$", ruleType = RuleType.PATTERN, ruleValue = "email"))
        platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = "address.code", ruleType = RuleType.SCRIPT, ruleValue = "RANDOM_INT(100,200)"))

        val result = engine.generate(repoId, "GET", "/users/x")!!
        val body = SpecModelIO.parseToTree(result.response.body)
        assertEquals("上海", body.path("address")?.path("city")?.asText(), "value 规则覆盖 object 字段")
        val code = body.path("address")?.path("code")?.asInt()
        assertTrue(code in 100..200, "script RANDOM_INT 应返回区间内整数，实际 $code")
        assertEquals("user@example.com", body.path("email")?.asText(), "pattern 类型按格式关键字生成样例")
    }

    @Test
    fun `api 级规则优先于项目级`() {
        val (engine, platform, repoId) = setup()
        platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = "address.city", ruleType = RuleType.VALUE, ruleValue = "上海"))
        val getUser = engine.generate(repoId, "GET", "/users/x")!!
        platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = getUser.apiId, fieldPattern = "address.city", ruleType = RuleType.VALUE, ruleValue = "北京"))
        val body = SpecModelIO.parseToTree(engine.generate(repoId, "GET", "/users/x")!!.response.body)
        assertEquals("北京", body.path("address")?.path("city")?.asText(), "api 级规则优先")
    }
}