package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * OpenAPI 3.1 内部模型 —— 存储内核的唯一数据模型。
 * 所有外部格式（2.0/3.0/3.1/Postman）在摄入边界归一化为本模型（见 spec-parser）。
 */
data class OpenApiDoc(
    val openapi: String = "3.1.1",
    val info: Info = Info(title = "", version = ""),
    val servers: List<Server> = emptyList(),
    val paths: Map<String, PathItem> = emptyMap(),
    val components: Components = Components(),
)

data class Info(
    val title: String,
    val version: String,
    val description: String? = null,
)

data class Server(
    val url: String,
    val description: String? = null,
)

enum class HttpMethod { GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE }

@JsonDeserialize(using = PathItemDeserializer::class)
data class PathItem(
    val summary: String? = null,
    val description: String? = null,
    /** path 下的各 http method → Operation */
    val operations: Map<HttpMethod, Operation> = emptyMap(),
)

data class Operation(
    val operationId: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val parameters: List<Parameter> = emptyList(),
    val requestBody: RequestBody? = null,
    val responses: Map<String, Response> = emptyMap(),
    val deprecated: Boolean = false,
)

data class Parameter(
    val name: String,
    /** query / path / header / cookie */
    @get:JsonProperty("in")
    val location: String,
    val required: Boolean = false,
    val description: String? = null,
    val schema: Schema? = null,
)

data class RequestBody(
    val description: String? = null,
    val content: Map<String, MediaType> = emptyMap(),
    val required: Boolean = false,
)

data class MediaType(
    val schema: Schema? = null,
    val example: Any? = null,
    val examples: Map<String, Example> = emptyMap(),
)

data class Example(
    val summary: String? = null,
    val description: String? = null,
    val value: Any? = null,
)

data class Response(
    val description: String,
    val content: Map<String, MediaType> = emptyMap(),
)

data class Components(
    val schemas: Map<String, Schema> = emptyMap(),
    val responses: Map<String, Response> = emptyMap(),
    val parameters: Map<String, Parameter> = emptyMap(),
    val requestBodies: Map<String, RequestBody> = emptyMap(),
    val examples: Map<String, Example> = emptyMap(),
)