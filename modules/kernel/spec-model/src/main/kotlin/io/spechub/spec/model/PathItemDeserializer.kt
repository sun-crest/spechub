package io.spechub.spec.model

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import java.util.LinkedHashMap

/**
 * PathItem 双格式反序列化 —— 让 spec-model 能直读标准 OpenAPI 3.1 文档：
 *  - 标准形状：`get` / `post` / `put` 等扁平小写 key → operations map（直读通道使用）
 *  - 内部规范化形状：`operations` map（round-trip / 索引重写使用）
 * 其余 pathItem 级字段（parameters / servers 等）P0 阶段忽略。
 */
class PathItemDeserializer : JsonDeserializer<PathItem>() {
    private val opsType = object : TypeReference<LinkedHashMap<HttpMethod, Operation>>() {}

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): PathItem {
        if (p.currentToken != JsonToken.START_OBJECT) {
            throw JsonMappingException.from(p, "PathItem 应为 object")
        }
        var summary: String? = null
        var description: String? = null
        val operations = LinkedHashMap<HttpMethod, Operation>()
        while (p.nextToken() != JsonToken.END_OBJECT) {
            val field = p.currentName ?: ""
            p.nextToken()
            when {
                field == "summary" -> summary = p.valueAsString
                field == "description" -> description = p.valueAsString
                field == "operations" -> {
                    val inner: LinkedHashMap<HttpMethod, Operation>? = p.readValueAs(opsType)
                    if (inner != null) operations.putAll(inner) else p.skipChildren()
                }
                field == "parameters" -> p.skipChildren() // pathItem 级参数，P0 忽略
                else -> {
                    val method = runCatching { HttpMethod.valueOf(field.uppercase()) }.getOrNull()
                    if (method != null) {
                        operations[method] = p.readValueAs(Operation::class.java)
                    } else {
                        p.skipChildren()
                    }
                }
            }
        }
        return PathItem(summary = summary, description = description, operations = operations)
    }
}