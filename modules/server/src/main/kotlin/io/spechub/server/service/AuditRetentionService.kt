package io.spechub.server.service

import io.spechub.server.config.SpecHubProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** C7：每日清理超过 `spechub.audit-retention-days`（默认 365 天）的审计事件。 */
@Service
class AuditRetentionService(
    private val jdbc: JdbcTemplate,
    private val props: SpecHubProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    fun cleanup(): Int {
        val before = Timestamp.from(Instant.now().minus(Duration.ofDays(props.auditRetentionDays)))
        val deleted = jdbc.update("DELETE FROM audit_event WHERE created_at < ?", before)
        if (deleted > 0) log.info("清理 audit_event：删除 {} 条超过 {} 天的记录", deleted, props.auditRetentionDays)
        return deleted
    }
}
