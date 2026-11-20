package io.spechub.server.service

import io.spechub.server.repository.AuditEventEntity
import io.spechub.server.repository.AuditEventRepository
import io.spechub.server.security.AppUserPrincipal
import io.spechub.server.security.OidcAppUserPrincipal
import io.spechub.server.security.ServicePrincipal
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.AuditEventRecord
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * C7：管理动作审计。
 *
 * - `record` 在调用方事务内写 `audit_event`；管理动作（用户 / 令牌 / 服务账号等）写失败随事务回滚。
 * - 登录 / 登出 / 限流类认证事件用 [recordAuth] **尽力而为**（失败只记 ERROR，不影响登录响应）。
 * - `detail` 截断 1024 字符；调用方只传摘要，禁止密码 / 令牌 / Cookie / spec 正文。
 * - 查询固定 org，默认最近 30 天，limit 默认 50 / 最大 200；审计查询本身不写审计。
 */
@Service
class AuditService(
    private val events: AuditEventRepository,
    private val jdbc: JdbcTemplate,
    private val users: UserService,
    private val orgs: OrgService,
    txManager: PlatformTransactionManager,
) {
    private val authTransaction =
        TransactionTemplate(txManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    companion object {
        const val ACTOR_USER = "user"
        const val ACTOR_SERVICE = "service_account"
        const val ACTOR_ANONYMOUS = "anonymous"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILURE = "failure"
        const val DEFAULT_QUERY_DAYS = 30L
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        private const val MAX_DETAIL = 1024

        private val OUTCOMES = setOf(OUTCOME_SUCCESS, OUTCOME_FAILURE)
        private val ACTOR_TYPES = setOf(ACTOR_USER, ACTOR_SERVICE, ACTOR_ANONYMOUS)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    data class AuditActor(
        val userId: Long?,
        val name: String,
        val type: String,
    ) {
        companion object {
            val ANONYMOUS = AuditActor(null, "anonymous", ACTOR_ANONYMOUS)
        }
    }

    fun currentActor(): AuditActor = actorFor(SecurityContextHolder.getContext().authentication)

    fun actorFor(authentication: Authentication?): AuditActor {
        val principal = authentication?.principal
        return when (principal) {
            is AppUserPrincipal -> AuditActor(principal.userId, principal.username, ACTOR_USER)
            is OidcAppUserPrincipal -> AuditActor(principal.appUserId, principal.username, ACTOR_USER)
            is ServicePrincipal -> AuditActor(null, "service:${principal.name}", ACTOR_SERVICE)
            else -> AuditActor.ANONYMOUS
        }
    }

    /** 登录失败（无 SecurityContext）时按尝试的 username / email 识别 actor；C 单组织用默认 org。 */
    fun actorForIdentifier(identifier: String?): AuditActor {
        if (identifier.isNullOrBlank()) return AuditActor.ANONYMOUS
        val user = users.findByUsername(identifier) ?: users.findByEmail(identifier)
        return if (user != null) {
            AuditActor(user.id, user.username, ACTOR_USER)
        } else {
            AuditActor(null, identifier.take(128), ACTOR_ANONYMOUS)
        }
    }

    fun defaultOrgId(): Long = orgs.defaultOrgId()

    fun currentRequest(): HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    @Transactional
    fun record(
        orgId: Long,
        actor: AuditActor,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
        outcome: String = OUTCOME_SUCCESS,
        request: HttpServletRequest? = currentRequest(),
    ) {
        events.save(buildEntity(orgId, actor, action, targetType, targetId, detail, outcome, request))
    }

    /** 当前 principal + request 的便捷入口（管理动作）。 */
    fun recordCurrent(
        orgId: Long,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
        outcome: String = OUTCOME_SUCCESS,
    ) {
        record(orgId, currentActor(), action, targetType, targetId, detail, outcome, currentRequest())
    }

    /** 认证 / 限流事件：独立事务尽力而为，不因审计写失败影响登录 / 登出响应，也不会污染业务事务。 */
    fun recordAuth(
        orgId: Long,
        actor: AuditActor,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        detail: String? = null,
        outcome: String = OUTCOME_SUCCESS,
        request: HttpServletRequest?,
    ) {
        try {
            authTransaction.executeWithoutResult {
                events.save(buildEntity(orgId, actor, action, targetType, targetId, detail, outcome, request))
            }
        } catch (e: Exception) {
            log.error("认证审计写入失败 action={} actor={}", action, actor.name, e)
        }
    }

    private fun buildEntity(
        orgId: Long,
        actor: AuditActor,
        action: String,
        targetType: String?,
        targetId: String?,
        detail: String?,
        outcome: String,
        request: HttpServletRequest?,
    ): AuditEventEntity {
        if (outcome !in OUTCOMES) throw IllegalArgumentException("非法审计 outcome：$outcome")
        val actorType = actor.type.takeIf { it in ACTOR_TYPES } ?: ACTOR_USER
        return AuditEventEntity(
            orgId = orgId,
            actorUserId = actor.userId,
            actorName = actor.name.take(128),
            actorType = actorType,
            action = action.take(64),
            targetType = targetType?.take(64),
            targetId = targetId?.take(128),
            detail = detail?.take(MAX_DETAIL),
            outcome = outcome,
            ip = request?.remoteAddr?.take(64),
            userAgent = request?.getHeader("User-Agent")?.take(512),
            createdAt = Instant.now(),
        )
    }

    fun list(
        orgId: Long,
        from: Instant?,
        to: Instant?,
        actor: String?,
        action: String?,
        targetType: String?,
        outcome: String?,
        limit: Int,
        offset: Int,
    ): List<AuditEventRecord> {
        if (outcome != null && outcome !in OUTCOMES) {
            throw ApiException(ApiErrorCode.VALIDATION, "outcome ∈ success | failure")
        }
        val resolvedTo = to ?: Instant.now()
        val resolvedFrom = from ?: resolvedTo.minus(Duration.ofDays(DEFAULT_QUERY_DAYS))
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw ApiException(ApiErrorCode.VALIDATION, "from 不能晚于 to")
        }
        val where = mutableListOf("org_id = ?")
        val args = mutableListOf<Any>(orgId, Timestamp.from(resolvedFrom), Timestamp.from(resolvedTo))
        where += "created_at >= ?"
        where += "created_at <= ?"

        fun filter(
            column: String,
            value: String?,
        ) {
            if (!value.isNullOrBlank()) {
                where += "$column = ?"
                args += value
            }
        }
        filter("actor_name", actor)
        filter("action", action)
        filter("target_type", targetType)
        filter("outcome", outcome)

        val sql =
            "SELECT id, actor_name, actor_type, action, target_type, target_id, detail, outcome, ip, created_at " +
                "FROM audit_event WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
        args += limit
        args += offset
        return jdbc.query(sql, { rs, _ ->
            AuditEventRecord(
                id = rs.getLong("id"),
                actorName = rs.getString("actor_name"),
                actorType = rs.getString("actor_type"),
                action = rs.getString("action"),
                targetType = rs.getString("target_type"),
                targetId = rs.getString("target_id"),
                detail = rs.getString("detail"),
                outcome = rs.getString("outcome"),
                ip = rs.getString("ip"),
                createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            )
        }, *args.toTypedArray())
    }
}
