package io.spechub.server.web

import io.spechub.server.service.AuditService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.AuditEventRecord
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * C7：管理动作审计查询（仅 org_admin）。
 * - 固定当前 org，禁止跨 org；
 * - 按 created_at DESC, id DESC；
 * - 查询本身不写审计（避免自增循环），只记 INFO 日志。
 */
@RestController
@RequestMapping("/api/audit")
class AuditController(
    private val auth: AuthService,
    private val audit: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(
        request: HttpServletRequest,
        @RequestParam from: String? = null,
        @RequestParam to: String? = null,
        @RequestParam actor: String? = null,
        @RequestParam action: String? = null,
        @RequestParam targetType: String? = null,
        @RequestParam outcome: String? = null,
        @RequestParam limit: Int = AuditService.DEFAULT_LIMIT,
        @RequestParam offset: Int = 0,
    ): List<AuditEventRecord> {
        auth.requireOrgAdmin(request, "查看审计日志")
        val orgId = auth.currentOrgId(request)
        val safeLimit = limit.coerceIn(1, AuditService.MAX_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        log.info(
            "审计查询 org={} actor={} action={} targetType={} outcome={} limit={} offset={}",
            orgId,
            actor,
            action,
            targetType,
            outcome,
            safeLimit,
            safeOffset,
        )
        return audit.list(
            orgId = orgId,
            from = parseInstant(from, "from"),
            to = parseInstant(to, "to"),
            actor = actor,
            action = action,
            targetType = targetType,
            outcome = outcome,
            limit = safeLimit,
            offset = safeOffset,
        )
    }

    private fun parseInstant(
        value: String?,
        param: String,
    ): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "$param 必须是 ISO-8601 Instant",
                listOf(ApiErrorBody.FieldError(param, "ISO-8601 Instant")),
            )
        }
    }
}
