package io.spechub.server.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * 每日清理已过期 / 已吊销超过 7 天的 `user_token`（C4）。
 * 会话由 Spring Session 自行过期；审计事件不在此删除（C7 保留策略）。
 */
@Service
class TokenCleanupService(
    private val jdbc: JdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    fun cleanup() {
        val now = Timestamp.from(Instant.now())
        val revokedBefore = Timestamp.from(Instant.now().minus(Duration.ofDays(7)))
        val deleted =
            jdbc.update(
                "DELETE FROM user_token WHERE " +
                    "(expires_at IS NOT NULL AND expires_at < ?) OR " +
                    "(revoked_at IS NOT NULL AND revoked_at < ?)",
                now,
                revokedBefore,
            )
        if (deleted > 0) log.info("清理 user_token：删除 {} 条过期 / 吊销超过 7 天的令牌", deleted)
    }
}
