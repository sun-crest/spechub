package io.spechub.spec.model.io

import io.spechub.spec.model.Components
import io.spechub.spec.model.Info
import io.spechub.spec.model.Operation
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.PathItem
import io.spechub.spec.model.Schema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** B3：归一化模型指纹 specHash 的性质（稳定 + 敏感） */
class SpecHashTest {

    private fun specimen(swapped: Boolean = false): OpenApiDoc {
        val pathA = "/a" to PathItem(get = Operation(operationId = "opA"))
        val pathB = "/b" to PathItem(post = Operation(operationId = "opB"))
        val schemas = linkedMapOf(
            "S1" to Schema(type = Schema.typeArray("object"), properties = linkedMapOf("id" to Schema(type = Schema.typeArray("integer")))),
            "S2" to Schema(type = Schema.typeArray("string")),
        )
        val paths = if (swapped) linkedMapOf(pathB, pathA) else linkedMapOf(pathA, pathB)
        val s = if (swapped) linkedMapOf("S2" to schemas.getValue("S2"), "S1" to schemas.getValue("S1")) else schemas
        return OpenApiDoc(
            info = Info(title = "hash-demo", version = "1.0.0"),
            paths = paths,
            components = Components(schemas = s),
        )
    }

    @Test
    fun `hash 对字段顺序不敏感`() {
        assertEquals(SpecHash.of(specimen(swapped = false)), SpecHash.of(specimen(swapped = true)))
    }

    @Test
    fun `hash 对数值笔法不敏感`() {
        fun with(maximum: String): OpenApiDoc = OpenApiDoc(
            info = Info(title = "t", version = "1"),
            components = Components(schemas = linkedMapOf("S" to Schema(type = Schema.typeArray("number"), maximum = BigDecimal(maximum)))),
        )
        assertEquals(SpecHash.of(with("1")), SpecHash.of(with("1.00")))
        assertEquals(SpecHash.of(with("0.5")), SpecHash.of(with("0.50")))
    }

    @Test
    fun `hash 对语义变化敏感`() {
        fun with(desc: String): OpenApiDoc = OpenApiDoc(
            info = Info(title = "t", version = "1", description = desc),
        )
        assertNotEquals(SpecHash.of(with("a")), SpecHash.of(with("b")))
    }

    @Test
    fun `hash 对文档变更敏感（路径与操作差异）`() {
        val a = specimen()
        val b = specimen().copy(
            paths = linkedMapOf(
                "/a" to PathItem(get = Operation(operationId = "opA", summary = "changed")),
                "/b" to PathItem(post = Operation(operationId = "opB")),
            ),
        )
        assertNotEquals(SpecHash.of(a), SpecHash.of(b))
    }

    @Test
    fun `hash 为定长小写十六进制`() {
        val h = SpecHash.of(specimen())
        assertAll(
            { assertEquals(64, h.length) },
            { assertEquals(h, h.lowercase()) },
            { assertEquals(h, h.filter { it.isDigit() || it in 'a'..'f' }) },
        )
    }

    @Test
    fun `规范化字符串吸收顺序与数字笔法`() {
        assertEquals(
            // {"a":[1,2,"x"],"b":1}  等价于 连带 1.0
            SpecHash.canonicalString(
                SpecModelIO.parseToTree("""{"b":1.0,"a":[1,2.00,"x"]}""", SpecFormat.JSON),
            ),
            SpecHash.canonicalString(
                SpecModelIO.parseToTree("""{"a":[1,2,"x"],"b":1}""", SpecFormat.JSON),
            ),
        )
    }
}