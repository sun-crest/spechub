package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

/*
 * SpecHub kernel · spec-model —— OpenAPI 3.1 内部模型（★ 零框架依赖纯库）
 *
 * 设计约定（产品方案 §6.3 / §6.5）：
 * - 内部只存在一个模型：OpenAPI 3.1。2.0 / 3.0 一律在摄入边界（spec-parser）升级映射后进入本模型。
 * - 全部 Kotlin data class + 空安全；形式自由的值（example/default/enum/const/…）用 JsonNode 保真。
 * - 类级 @JsonInclude(NON_EMPTY)：序列化省略 null / 空集合，round-trip 稳定，也为 specHash 去噪。
 * - Map 字段保持插入序（LinkedHashMap）；指纹归一化时统一排序，语义等价漂移被吸收。
 */

/** OpenAPI 3.1 文档根（内部只要是 3.1） */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class OpenApiDoc(
    /** 规范版本；内部恒为 3.1（摄入边界负责升级，见 spec-parser C 组） */
    val openapi: String = "3.1.0",
    val info: Info,
    /** 3.1：默认 JSON Schema 方言 URL（`jsonSchemaDialect`） */
    val jsonSchemaDialect: String? = null,
    val servers: List<Server> = emptyList(),
    /** path → PathItem；保持源文档路径出现顺序 */
    val paths: Map<String, PathItem> = emptyMap(),
    /** 3.1 webhooks：名称 → PathItem（等价于 paths 的表达式形式） */
    val webhooks: Map<String, PathItem> = emptyMap(),
    val components: Components? = null,
    /** 全局安全要求：`[{schemeName: [scopes]}]` */
    val security: List<SecurityRequirement> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val externalDocs: ExternalDocs? = null,
)

/** 安全要求别名：scheme 名 → scope 列表 */
typealias SecurityRequirement = Map<String, List<String>>

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Info(
    val title: String,
    val summary: String? = null,
    val description: String? = null,
    val termsOfService: String? = null,
    val contact: Contact? = null,
    val license: License? = null,
    val version: String,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Contact(
    val name: String? = null,
    val url: String? = null,
    val email: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class License(
    val name: String,
    /** 3.1：SPDX 标识符（identifier 与 url 二选一） */
    val identifier: String? = null,
    val url: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Server(
    val url: String,
    val description: String? = null,
    val variables: Map<String, ServerVariable> = emptyMap(),
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ServerVariable(
    val `enum`: List<JsonNode> = emptyList(),
    val default: JsonNode? = null,
    val description: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Tag(
    val name: String,
    val description: String? = null,
    val externalDocs: ExternalDocs? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ExternalDocs(
    val description: String? = null,
    val url: String,
)