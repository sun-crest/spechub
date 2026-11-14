package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal

/*
 * Schema —— OpenAPI 3.1 的 Schema Object（§4.8.26，契约内容核心）。
 *
 * 关键设计（对齐 §6.5 归一化模型）：
 * - `type` 保留原始 JsonNode 形态（保真）：3.1 数组 `[string, 'null']` 或 3.0 单字符串都可承载，
 *   统一用 `typeNames()` 提取规范列表；3.0 的单一 `type`/`nullable` 由 spec-parser 在摄入边界
 *   升级为数组形态（C2），本模型不再有 `nullable` 字段。
 *   （注：Jackson 3 的 @JsonDeserializeAs 无法对集合类型窄化，故 type 用 JsonNode 保真 + 访问器。）
 * - `exclusiveMinimum/Maximum` 在 3.1 为数值（3.0 布尔形态由 C2 升级）。
 * - 形式自由的值（example/default/enum/const 等）用 JsonNode 保真。
 * - `additionalProperties` 用 JsonNode 表示（布尔或 Schema 两者皆可），配合 SpecModelIO 的
 *   类型辅助方法使用，避免为二义类型成对编写自定义序列化器。
 * - 宽松摄入：非标准字段被 Jackson 忽略（本研究刻意不 fail）。
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Schema(
    /** `type` 原始值（3.1 数组 / 3.0 单字符串 / 缺省）；用 [typeNames] 提取规范列表 */
    val type: JsonNode? = null,
    val format: String? = null,
    val title: String? = null,
    val description: String? = null,
    val default: JsonNode? = null,
    val deprecated: Boolean = false,
    val readOnly: Boolean = false,
    val writeOnly: Boolean = false,
    val examples: List<JsonNode> = emptyList(),
    val example: JsonNode? = null,
    val `enum`: List<JsonNode> = emptyList(),
    val const: JsonNode? = null,

    // ── 数值约束（3.1 exclusive* 为数值） ──
    val multipleOf: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val exclusiveMaximum: BigDecimal? = null,
    val minimum: BigDecimal? = null,
    val exclusiveMinimum: BigDecimal? = null,

    // ── 字符串约束 ──
    val maxLength: Int? = null,
    val minLength: Int? = null,
    val pattern: String? = null,

    // ── 数组/对象约束 ──
    val maxItems: Int? = null,
    val minItems: Int? = null,
    val uniqueItems: Boolean = false,
    val maxContains: Int? = null,
    val minContains: Int? = null,
    val maxProperties: Int? = null,
    val minProperties: Int? = null,
    val required: Set<String> = emptySet(),
    val properties: Map<String, Schema> = emptyMap(),
    val patternProperties: Map<String, Schema> = emptyMap(),
    /** 布尔或 Schema（JsonNode 保真；辅助函数见 SpecModelIO.additionalPropertiesSchemaOrFlag） */
    val additionalProperties: JsonNode? = null,
    val items: Schema? = null,
    /** 3.1 tuple 形态（配合 items: false 使用） */
    val prefixItems: List<Schema> = emptyList(),
    val contains: Schema? = null,
    val propertyNames: Schema? = null,

    // ── 组合关键字 ──
    val not: Schema? = null,
    val `if`: Schema? = null,
    val `then`: Schema? = null,
    val `else`: Schema? = null,
    val allOf: List<Schema> = emptyList(),
    val anyOf: List<Schema> = emptyList(),
    val oneOf: List<Schema> = emptyList(),
    /** JSON Schema 2020-12 局部定义（3.1 关键字 `$defs`） */
    @get:JsonProperty("\$defs")
    val defs: Map<String, Schema> = emptyMap(),

    // ── OpenAPI 扩展信息 ──
    val discriminator: Discriminator? = null,
    val xml: Xml? = null,
    val externalDocs: ExternalDocs? = null,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
) {
    companion object {
        /** 构造 3.1 数组形态的 type 值节点（`["integer", "null"]`） */
        fun typeArray(vararg names: String): ArrayNode =
            JsonNodeFactory.instance.arrayNode().apply { names.forEach(::add) }

        // 常用单类型速记（仍是 3.1 数组形态，与 C2 升级后的内部表示一致）
        val OBJECT: JsonNode get() = typeArray("object")
        val ARRAY: JsonNode get() = typeArray("array")
        val STRING: JsonNode get() = typeArray("string")
        val INTEGER: JsonNode get() = typeArray("integer")
        val NUMBER: JsonNode get() = typeArray("number")
        val BOOLEAN: JsonNode get() = typeArray("boolean")
        val NULL: JsonNode get() = typeArray("null")

        /** 可空类型速记：`typeArray(types..., "null")` */
        fun nullableOf(vararg types: String): JsonNode = typeArray(*types, "null")
    }

    /** 规范类型名列表：`type` 为数组或单字符串均可提取；缺失/非法时返回 null（无类型约束） */
    fun typeNames(): List<String>? = when (val t = type) {
        null, is NullNode -> null
        is StringNode -> listOf(t.stringValue())
        else -> if (t.isArray) {
            val names = t.mapNotNull { (it as? StringNode)?.stringValue() }
            if (names.isEmpty()) null else names
        } else {
            null
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Discriminator(
    val propertyName: String,
    val mapping: Map<String, String> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Xml(
    val name: String? = null,
    val namespace: String? = null,
    val prefix: String? = null,
    val attribute: Boolean = false,
    val wrapped: Boolean = false,
)