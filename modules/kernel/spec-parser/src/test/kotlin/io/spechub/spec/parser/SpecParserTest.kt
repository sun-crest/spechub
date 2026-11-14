package io.spechub.spec.parser

import io.spechub.spec.model.io.AdditionalPropertiesValue
import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C1–C4：版本探测 / 3.0→3.1 语义升级 / 变更日志 / 错误容错 */
class SpecParserTest {

    private fun loadFixture(path: String): String =
        checkNotNull(javaClass.classLoader.getResource(path)).readText()

    // ── C1 · 版本探测与直通 ──────────────────────────────────────────────

    @Test
    fun `3_1 文档直通内部模型，无升级日志`() {
        val source = "openapi: 3.1.0\ninfo: { title: t, version: \"1\" }\npaths: {}\n"
        val result = SpecParser.parse(source)
        assertTrue(result is ParseResult.Success, "应成功：$result")
        assertEquals("t", result.doc.info.title)
        assertTrue(result.upgradeLog.isEmpty(), "3.1 不应产生升级日志")
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `版本探测区分 3_1 3_0 与其它`() {
        fun detected(root: String) = SpecParser.detect(SpecModelIO.parseToTree(root, SpecFormat.JSON))
        assertTrue(detected("""{"openapi":"3.1.1"}""") == DetectedVersion.OPENAPI_31)
        assertTrue(detected("""{"openapi":"3.0.3"}""") == DetectedVersion.OPENAPI_30)
        assertTrue(detected("""{"swagger":"2.0"}""") == DetectedVersion.SWAGGER_20)
        assertTrue(detected("""{"openapi":"4.0.0"}""") == DetectedVersion.UNKNOWN)
        assertTrue(detected("""{}""") == DetectedVersion.NONE)
    }

    // ── C2 · 3.0 → 3.1 语义升级 ──────────────────────────────────────────

    @Test
    fun `3_0 升级 nullable 与 exclusive 边界`() {
        val result = SpecParser.parse(loadFixture("openapi/PetStore-3.0.yaml"))
        assertTrue(result is ParseResult.Success, "升级应成功：${result.issues}")
        val doc = result.doc

        val get = doc.paths.getValue("/pets").get
        assertTrue(get != null)
        // 参数 schema：type: integer + nullable → [integer, null]
        assertEquals(listOf("integer", "null"), get.parameters.single().schema?.typeNames())

        val respSchema = get.responses.getValue("200").content.getValue("application/json").schema!!
        assertEquals(listOf("integer", "null"), respSchema.properties.getValue("id").typeNames())
        // exclusiveMinimum: true + minimum: 0 → 数值 0，minimum 移除
        val count = respSchema.properties.getValue("count")
        assertEquals(BigDecimal("0"), count.exclusiveMinimum)
        assertEquals(null, count.minimum, "3.1 exclusiveMinimum 数值已含语义，minimum 应移除")

        val pet = doc.components!!.schemas.getValue("Pet")
        assertEquals(listOf("integer", "null"), pet.properties.getValue("age").typeNames())
        assertEquals(listOf("string"), pet.properties.getValue("tag").typeNames(), "nullable: false 不应加 null")

        // additionalProperties 子 Schema 也被升级（type: string → [string]）
        val ap = SpecModelIO.additionalPropertiesOf(pet.additionalProperties)
        assertEquals(listOf("string"), (ap as AdditionalPropertiesValue.ObjectSchema).schema.typeNames())
    }

    @Test
    fun `3_0 嵌套 properties 中的 nullable 递归升级`() {
        val result = SpecParser.parse(loadFixture("openapi/PetStore-3.0.yaml"))
        assertTrue(result is ParseResult.Success)
        val pet = result.doc.components!!.schemas.getValue("Pet")
        assertEquals(listOf("string"), pet.properties.getValue("name").typeNames())
        assertEquals(listOf("integer", "null"), pet.properties.getValue("age").typeNames())
    }

    // ── C3 · 升级变更日志 ────────────────────────────────────────────────

    @Test
    fun `升级日志记录 nullable type exclusive 转换`() {
        val result = SpecParser.parse(loadFixture("openapi/PetStore-3.0.yaml"))
        assertTrue(result is ParseResult.Success)
        val kinds = result.upgradeLog.map { it.kind }.toSet()
        assertAll(
            { assertTrue(UpgradeKind.NULLABLE_TO_TYPE in kinds, "应有 nullable 升级：$kinds") },
            { assertTrue(UpgradeKind.TYPE_ARRAY in kinds, "应有 type 数组化：$kinds") },
            { assertTrue(UpgradeKind.EXCLUSIVE_BOUND in kinds, "应有 exclusive 升级：$kinds") },
            { assertTrue(result.upgradeLog.count { it.kind == UpgradeKind.NULLABLE_TO_TYPE } >= 3) },
            { assertTrue(result.issues.none { it.severity == Severity.ERROR }, "无错误，最多警告") },
        )
    }

    @Test
    fun `nullable 无 type 时标记需人工复核`() {
        val result = SpecParser.parse(
            """
            openapi: 3.0.0
            info: { title: t, version: "1" }
            paths:
              /x:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema:
                            nullable: true
            """.trimIndent(),
        )
        assertTrue(result is ParseResult.Success)
        assertTrue(result.upgradeLog.any { it.kind == UpgradeKind.NULLABLE_TO_TYPE && it.review == ReviewFlag.NEEDS_REVIEW })
        assertTrue(result.issues.any { it.kind == "nullable-without-type" })
    }

    // ── C4 · 失败容错与错误收集 ──────────────────────────────────────────

    @Test
    fun `Swagger 2_0 明确拒绝且收集到错误`() {
        val result = SpecParser.parse("swagger: \"2.0\"\ninfo: { title: t, version: \"1\" }\npaths: {}\n")
        assertTrue(result is ParseResult.Failure)
        assertEquals("unsupported-version", result.issues.single().kind)
    }

    @Test
    fun `不可识别版本与缺失版本均结构化失败`() {
        val unknown = SpecParser.parse("""{"openapi":"4.0.0","paths":{}}""", SpecFormat.JSON)
        assertEquals("unknown-version", (unknown as ParseResult.Failure).issues.single().kind)

        val missing = SpecParser.parse("""{"info":{"title":"t","version":"1"},"paths":{}}""", SpecFormat.JSON)
        assertEquals("not-openapi", (missing as ParseResult.Failure).issues.single().kind)
    }

    @Test
    fun `坏的 YAML 语法不抛异常改为结构化失败`() {
        val result = SpecParser.parse("openapi: [incomplete\n  ::\n")
        assertTrue(result is ParseResult.Failure)
        assertEquals("syntax", result.issues.single().kind)
    }
}