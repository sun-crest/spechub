package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/*
 * PathItem（路径项）与 Operation（操作）—— OpenAPI 3.1 §4.7 / §4.8。
 * HTTP 方法字段按规范建议顺序声明（GET/PUT/POST/DELETE/OPTIONS/HEAD/PATCH/TRACE），
 * 使序列化输出稳定可读；未知方法（如 WebSocket 扩展）由摄入层容错忽略。
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class PathItem(
    val summary: String? = null,
    val description: String? = null,
    val get: Operation? = null,
    val put: Operation? = null,
    val post: Operation? = null,
    val delete: Operation? = null,
    val options: Operation? = null,
    val head: Operation? = null,
    val patch: Operation? = null,
    val trace: Operation? = null,
    /** 可在此作用于 path 下所有方法（无 method 级别参数时） */
    val parameters: List<Parameter> = emptyList(),
    val servers: List<Server> = emptyList(),
    @get:JsonProperty("\$ref")
    val ref: String? = null,
) {
    /** 本路径项存在的全部 [方法名 → Operation] 对（按声明顺序） */
    fun operations(): List<Pair<String, Operation>> = buildList {
        listOf(
            "get" to get, "put" to put, "post" to post, "delete" to delete,
            "options" to options, "head" to head, "patch" to patch, "trace" to trace,
        ).forEach { (name, op) -> if (op != null) add(name to op) }
    }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Operation(
    val operationId: String? = null,
    val tags: List<String> = emptyList(),
    val summary: String? = null,
    val description: String? = null,
    val externalDocs: ExternalDocs? = null,
    val parameters: List<Parameter> = emptyList(),
    val requestBody: RequestBody? = null,
    /** 状态码字符串（如 "200"、范围 "2XX"）或 "default" → Response */
    val responses: Map<String, Response> = emptyMap(),
    val deprecated: Boolean = false,
    /** 覆盖全局 security；null 表示未声明（沿用全局） */
    val security: List<SecurityRequirement>? = null,
    val servers: List<Server> = emptyList(),
    val callbacks: Map<String, Callback> = emptyMap(),
) {
    /** 便捷：按整数状态码取响应 */
    fun responseByCode(code: Int): Response? = responses[code.toString()]

    fun defaultResponse(): Response? = responses["default"]

    /** 全部参数（含 PathItem 级别继承的），顺序 = 路径级参数 + 操作级参数 */
    fun allParameters(pathItem: PathItem): List<Parameter> = pathItem.parameters + parameters
}