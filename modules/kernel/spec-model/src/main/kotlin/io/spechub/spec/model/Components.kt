package io.spechub.spec.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/*
 * Components —— OpenAPI 3.1 §4.8.27 可复用组件容器。
 * 各容器键值 `Map<String, X>`；为兼容宽松摄入，组件名不做校验（规范要求 ^[a-zA-Z0-9._-]+$）。
 */

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Components(
    val schemas: Map<String, Schema> = emptyMap(),
    val responses: Map<String, Response> = emptyMap(),
    val parameters: Map<String, Parameter> = emptyMap(),
    val examples: Map<String, Example> = emptyMap(),
    val requestBodies: Map<String, RequestBody> = emptyMap(),
    val headers: Map<String, Header> = emptyMap(),
    val securitySchemes: Map<String, SecurityScheme> = emptyMap(),
    val links: Map<String, Link> = emptyMap(),
    val pathItems: Map<String, PathItem> = emptyMap(),
    val callbacks: Map<String, Callback> = emptyMap(),
)

/** Callback 对象：运行时表达式 → PathItem */
typealias Callback = Map<String, PathItem>

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class SecurityScheme(
    val type: String,
    val description: String? = null,
    /** apiKey 用：参数名 */
    val name: String? = null,
    /** apiKey 用：query / header / cookie */
    val `in`: String? = null,
    /** http 用：方案（basic / bearer 等） */
    val scheme: String? = null,
    val bearerFormat: String? = null,
    /** oauth2 用 */
    val flows: OAuthFlows? = null,
    /** openIdConnect 用 */
    val openIdConnectUrl: String? = null,
    @get:JsonProperty("\$ref")
    val ref: String? = null,
) {
    companion object {
        const val TYPE_API_KEY = "apiKey"
        const val TYPE_HTTP = "http"
        const val TYPE_OAUTH2 = "oauth2"
        const val TYPE_OPEN_ID_CONNECT = "openIdConnect"
        const val TYPE_MUTUAL_TLS = "mutualTLS"
    }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class OAuthFlows(
    val implicit: OAuthFlow? = null,
    val password: OAuthFlow? = null,
    val clientCredentials: OAuthFlow? = null,
    val authorizationCode: OAuthFlow? = null,
)

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class OAuthFlow(
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val refreshUrl: String? = null,
    val scopes: Map<String, String> = emptyMap(),
)