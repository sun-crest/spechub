package io.spechub.server.web.common

/*
 * R1 —— 统一错误响应体（规范 §2.2）。
 * `instance` = 请求 URI（由 handler / filter 填充）；`fieldErrors` 校验类错误携带，否则 null。
 * 此结构同时被 filter 层（TokenAuthFilter）与 advice 层共用，保证全链路同构。
 */

data class ApiErrorBody(
    val status: Int,
    val code: String,
    val message: String,
    val instance: String?,
    val fieldErrors: List<FieldError>? = null,
) {
    data class FieldError(
        val field: String,
        val message: String,
    )
}
