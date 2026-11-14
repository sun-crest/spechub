package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

/*
 * Parameter / RequestBody / MediaType / Encoding —— OpenAPI 3.1 §4.8 / §4.9 / §4.10 / §4.15。
 * `in` 用 String 而非枚举：真实世界文档偶有非标准取值，String 换取摄入端容错（C4）。
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Parameter(
    val name: String,
    val `in`: String,
    val description: String? = null,
    val required: Boolean = false,
    val deprecated: Boolean = false,
    val allowEmptyValue: Boolean = false,
    val style: String? = null,
    val explode: Boolean? = null,
    val allowReserved: Boolean = false,
    /** schema 与 content 二选一（3.1：禁止同时出现），模型保留两者由校验层约束 */
    val schema: Schema? = null,
    val content: Map<String, MediaType> = emptyMap(),
    val example: JsonNode? = null,
    val examples: Map<String, Example> = emptyMap(),
    @get:JsonProperty("\$ref")
    val ref: String? = null,
) {
    companion object {
        const val IN_QUERY = "query"
        const val IN_PATH = "path"
        const val IN_HEADER = "header"
        const val IN_COOKIE = "cookie"
    }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class RequestBody(
    val description: String? = null,
    val content: Map<String, MediaType> = emptyMap(),
    val required: Boolean = false,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
) {
    /** 便捷：指定媒体类型的 schema（取第一个匹配的 schema，无则 null） */
    fun firstSchema(): Schema? =
        content.values.firstNotNullOfOrNull { it.schema } ?: refSchema()

    private fun refSchema(): Schema? = null
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class MediaType(
    val schema: Schema? = null,
    val example: JsonNode? = null,
    val examples: Map<String, Example> = emptyMap(),
    val encoding: Map<String, Encoding> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Encoding(
    val contentType: String? = null,
    val headers: Map<String, Header> = emptyMap(),
    val style: String? = null,
    val explode: Boolean? = null,
    val allowReserved: Boolean = false,
)