package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.math.BigDecimal

/**
 * OpenAPI 3.1 Schema（子集，覆盖 P0/P1 核心；按需扩展）。
 * 3.1 语义：type 可为数组（含 "null"）；exclusiveMinimum/Max 为数值（3.0 的布尔在归一化时转数值）。
 */
data class Schema(
    @get:JsonSerialize(using = SchemaTypeSerializer::class)
    @get:JsonDeserialize(using = SchemaTypeDeserializer::class)
    val type: List<String>? = null,
    val title: String? = null,
    val description: String? = null,
    val format: String? = null,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
    val properties: Map<String, Schema> = emptyMap(),
    val items: Schema? = null,
    val required: List<String> = emptyList(),
    @get:JsonProperty("enum")
    val enumValues: List<Any> = emptyList(),
    val minLength: Long? = null,
    val maxLength: Long? = null,
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val exclusiveMinimum: BigDecimal? = null,
    val exclusiveMaximum: BigDecimal? = null,
    @get:JsonProperty("\$defs")
    val defs: Map<String, Schema> = emptyMap(),
    val example: Any? = null,
    val default: Any? = null,
    /** true / false / Schema / Map<String, Schema> */
    val additionalProperties: Any? = null,
    val allOf: List<Schema> = emptyList(),
    val oneOf: List<Schema> = emptyList(),
    val anyOf: List<Schema> = emptyList(),
) {
    companion object {
        const val TYPE_STRING = "string"
        const val TYPE_NUMBER = "number"
        const val TYPE_INTEGER = "integer"
        const val TYPE_BOOLEAN = "boolean"
        const val TYPE_OBJECT = "object"
        const val TYPE_ARRAY = "array"
        const val TYPE_NULL = "null"

        /** 便捷构造：`type(type = "string")` 或 `type("integer", "null")` */
        fun of(first: String, vararg rest: String): Schema =
            Schema(type = listOf(first) + rest)
    }
}

/**
 * type 序列化：单个元素输出字符串（3.1 推荐），多个输出数组；"null" 强制写为带引号字符串。
 */
class SchemaTypeSerializer : JsonSerializer<List<*>>() {
    override fun serialize(value: List<*>?, gen: JsonGenerator, sp: SerializerProvider) {
        if (value.isNullOrEmpty()) { gen.writeNull(); return }
        if (value.size == 1) {
            gen.writeString(value[0] as String)
        } else {
            gen.writeStartArray()
            value.forEach { gen.writeString(it as String) }
            gen.writeEndArray()
        }
    }
}

/**
 * type 反序列化：字符串 → 单元素列表；数组中的 YAML `null` 标量 → "null" 字符串。
 */
class SchemaTypeDeserializer : JsonDeserializer<List<String>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): List<String> = when (p.currentToken) {
        JsonToken.VALUE_STRING -> listOf(p.valueAsString ?: TYPE_STRING_NORM)
        JsonToken.START_ARRAY -> {
            val result = mutableListOf<String>()
            p.nextToken()
            while (p.currentToken != JsonToken.END_ARRAY) {
                result += if (p.currentToken == JsonToken.VALUE_NULL) "null" else (p.valueAsString ?: "null")
                p.nextToken()
            }
            result
        }
        JsonToken.VALUE_NULL -> emptyList()
        else -> throw JsonMappingException.from(p, "OpenAPI type 必须是字符串或字符串数组")
    }

    private companion object {
        const val TYPE_STRING_NORM = "null"
    }
}