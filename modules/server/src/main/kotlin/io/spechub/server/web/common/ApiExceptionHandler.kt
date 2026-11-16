package io.spechub.server.web.common

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

/*
 * R1 —— 全局异常处理（规范 §2.3）：统一错误体 + 未知异常脱敏。
 * 匹配按窄优先：ApiException > 校验/绑定/报文 > ResponseStatusException（兜底兼容）> Exception（兜底）。
 * 未知异常响应体零内部细节（500 只给通用文案），全栈只进 ERROR 日志。
 */

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException, req: HttpServletRequest): ResponseEntity<ApiErrorBody> =
        respond(ApiErrorBody(e.status, e.code.name, e.message ?: "", req.requestURI, e.fieldErrors))

    /** Bean Validation 失败 → 字段级 fieldErrors（R2 起声明式校验接入） */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException, req: HttpServletRequest): ResponseEntity<ApiErrorBody> =
        respond(
            ApiErrorBody(
                ApiErrorCode.VALIDATION.status, ApiErrorCode.VALIDATION.name, "参数校验失败",
                req.requestURI,
                e.bindingResult.fieldErrors.map { ApiErrorBody.FieldError(it.field, it.defaultMessage ?: "非法值") },
            ),
        )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException, req: HttpServletRequest): ResponseEntity<ApiErrorBody> =
        respond(ApiErrorBody(ApiErrorCode.BAD_REQUEST.status, ApiErrorCode.BAD_REQUEST.name, "缺少请求参数：${e.parameterName}", req.requestURI))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException, req: HttpServletRequest): ResponseEntity<ApiErrorBody> =
        respond(ApiErrorBody(ApiErrorCode.BAD_REQUEST.status, ApiErrorCode.BAD_REQUEST.name, "参数 ${e.name} 类型不合法", req.requestURI))

    /** 请求体非法 JSON / 不可读 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(req: HttpServletRequest): ResponseEntity<ApiErrorBody> =
        respond(ApiErrorBody(ApiErrorCode.MALFORMED_BODY.status, ApiErrorCode.MALFORMED_BODY.name, "请求体 JSON 格式错误", req.requestURI))

    /** 兜底：残留的 ResponseStatusException（存量未迁移完 / 第三方抛）也统一为带 code 的响应 */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException, req: HttpServletRequest): ResponseEntity<ApiErrorBody> {
        val status = e.statusCode.value()
        return respond(ApiErrorBody(status, codeForStatus(status).name, e.reason ?: "请求失败", req.requestURI))
    }

    /** 未知异常：响应体脱敏（规范 §2.3 [强]），明细只进日志 */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, req: HttpServletRequest): ResponseEntity<ApiErrorBody> {
        log.error("未预期异常：{} {}", req.method, req.requestURI, e)
        return respond(ApiErrorBody(ApiErrorCode.INTERNAL.status, ApiErrorCode.INTERNAL.name, "服务器内部错误，请稍后重试", req.requestURI))
    }

    private fun respond(body: ApiErrorBody): ResponseEntity<ApiErrorBody> =
        ResponseEntity.status(body.status).body(body)

    companion object {
        private fun codeForStatus(status: Int): ApiErrorCode = when (status) {
            HttpStatus.BAD_REQUEST.value() -> ApiErrorCode.BAD_REQUEST
            HttpStatus.UNAUTHORIZED.value() -> ApiErrorCode.AUTH_UNAUTHORIZED
            HttpStatus.FORBIDDEN.value() -> ApiErrorCode.AUTH_FORBIDDEN
            HttpStatus.NOT_FOUND.value() -> ApiErrorCode.NOT_FOUND
            HttpStatus.CONFLICT.value() -> ApiErrorCode.CONFLICT
            else -> ApiErrorCode.INTERNAL
        }
    }
}