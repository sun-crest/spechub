package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

/*
 * Response / Header / Link / Example —— OpenAPI 3.1 §4.11 / §4.10 / §4.12 / §4.13。
 * Response.description 在规范中必填，但摄入端容错置为可空（宽进严出，见 §6.5）。
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Response(
    val description: String? = null,
    val headers: Map<String, Header> = emptyMap(),
    val content: Map<String, MediaType> = emptyMap(),
    val links: Map<String, Link> = emptyMap(),
    @get:JsonProperty("\$ref")
    val ref: String? = null,
)

/** Header 形状同 Parameter（无 name/in/required 除外），可 @$ref 引用 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Header(
    val description: String? = null,
    val required: Boolean = false,
    val deprecated: Boolean = false,
    val style: String? = null,
    val explode: Boolean? = null,
    val allowReserved: Boolean = false,
    val schema: Schema? = null,
    val content: Map<String, MediaType> = emptyMap(),
    val example: JsonNode? = null,
    val examples: Map<String, Example> = emptyMap(),
    @get:JsonProperty("\$ref")
    val ref: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Link(
    val operationRef: String? = null,
    val operationId: String? = null,
    /** 表达式 → 参数值 */
    val parameters: Map<String, JsonNode> = emptyMap(),
    val requestBody: JsonNode? = null,
    val description: String? = null,
    val server: Server? = null,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Example(
    val summary: String? = null,
    val description: String? = null,
    val value: JsonNode? = null,
    val externalValue: String? = null,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
)