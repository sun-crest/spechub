package io.spechub.server.web.common

/*
 * R1 —— 机器可分支的错误码（规范 §2.4：前缀族常量表，杜绝字符串散落）。
 * 每个 code 与 HTTP 状态强绑定；新增语义 → 在此追加枚举项，
 * 禁止在业务代码裸写错误码字符串。
 */

enum class ApiErrorCode(
    val status: Int,
) {
    /** 参数/请求语义非法（非校验框架产出的 400） */
    BAD_REQUEST(400),

    /** 声明式校验失败（fieldErrors 由 handler 填充） */
    VALIDATION(400),

    /** 请求体 JSON 不可读 */
    MALFORMED_BODY(400),

    /** 导入内容无法解析 */
    IMPORT_INVALID(400),

    /** 未认证（无有效令牌） */
    AUTH_UNAUTHORIZED(401),

    /** 已认证但角色不足 */
    AUTH_FORBIDDEN(403),

    /** 鉴权未启用的操作冲突（如登录） */
    AUTH_NOT_ENABLED(409),

    /** 资源/实体不存在 */
    NOT_FOUND(404),

    /** 状态冲突（已存在/已处理等） */
    CONFLICT(409),

    /** mock 无匹配接口 */
    MOCK_NO_MATCH(404),

    /** 上游（外部目标 / pandoc 子进程）失败 */
    UPSTREAM_ERROR(502),

    /** 服务不可用（软依赖缺失等） */
    SERVICE_UNAVAILABLE(503),

    /** 未预期异常（响应体脱敏，明细只进日志） */
    INTERNAL(500),
}
