package io.spechub.server.web.common

/*
 * R1 —— 统一业务异常（规范 §2.3）。Service / Controller 抛此异常，由 `ApiExceptionHandler` 集中映射；
 * 禁止再以「散抛 ResponseStatusException + 唯一中文 message」作为错误语义来源。
 * 校验类错误可携带 fieldErrors（R2 起 Bean Validation 的字段级错误经此透传）。
 */

class ApiException(
    val code: ApiErrorCode,
    message: String,
    val fieldErrors: List<ApiErrorBody.FieldError>? = null,
) : RuntimeException(message) {
    val status: Int get() = code.status
}