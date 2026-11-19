package io.spechub.server.security

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.security.core.AuthenticationException
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

/**
 * 登录限流（C3）：Redis 固定窗口，**fail-closed**。
 *
 * - 标识（username / email，忽略大小写）：5 次失败 / 5 分钟 → 锁 15 分钟；
 * - IP：20 次失败 / 5 分钟 → 锁 15 分钟；
 * - 登录成功清零该标识与 IP 的计数；
 * - Redis 不可用时抛 [LoginRateLimitUnavailableException]，登录返回 503，不回退内存限流。
 */
@Service
class RedisLoginRateLimiter(
    private val redis: StringRedisTemplate,
) {
    companion object {
        private const val PREFIX = "spechub:ratelimit:login"
        private const val WINDOW_MS = 5 * 60 * 1000L
        private const val LOCK_MS = 15 * 60 * 1000L
        private const val IDENTIFIER_LIMIT = 5L
        private const val IP_LIMIT = 20L

        /** 原子累加失败次数：窗口首次设置过期；达到阈值设置 15 分钟锁。 */
        private val FAILURE_SCRIPT =
            DefaultRedisScript(
                """
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then
                  redis.call('PEXPIRE', KEYS[1], ARGV[1])
                end
                if count >= tonumber(ARGV[2]) then
                  redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
                end
                return count
                """.trimIndent(),
                Long::class.java,
            )
    }

    /** @return 剩余锁定秒数（未锁定返回 null） */
    fun check(
        identifier: String,
        ip: String,
    ): Long? =
        try {
            listOf(identifierLockKey(identifier), ipLockKey(ip))
                .mapNotNull { retryAfter(it) }
                .maxOrNull()
        } catch (e: DataAccessException) {
            throw LoginRateLimitUnavailableException(e)
        }

    fun onFailure(
        identifier: String,
        ip: String,
    ) {
        try {
            registerFailure(identifierFailureKey(identifier), identifierLockKey(identifier), IDENTIFIER_LIMIT)
            registerFailure(ipFailureKey(ip), ipLockKey(ip), IP_LIMIT)
        } catch (e: DataAccessException) {
            throw LoginRateLimitUnavailableException(e)
        }
    }

    fun onSuccess(
        identifier: String,
        ip: String,
    ) {
        try {
            redis.delete(
                listOf(
                    identifierFailureKey(identifier),
                    identifierLockKey(identifier),
                    ipFailureKey(ip),
                    ipLockKey(ip),
                ),
            )
        } catch (e: DataAccessException) {
            throw LoginRateLimitUnavailableException(e)
        }
    }

    private fun registerFailure(
        failureKey: String,
        lockKey: String,
        limit: Long,
    ) {
        redis.execute(
            FAILURE_SCRIPT,
            listOf(failureKey, lockKey),
            WINDOW_MS.toString(),
            limit.toString(),
            LOCK_MS.toString(),
        )
    }

    private fun retryAfter(lockKey: String): Long? {
        if (redis.hasKey(lockKey) != true) return null
        val ttl = redis.getExpire(lockKey, TimeUnit.SECONDS)
        return if (ttl > 0) ttl else LOCK_MS / 1000
    }

    private fun normalize(identifier: String): String = identifier.trim().lowercase().ifBlank { "<unknown>" }

    private fun identifierFailureKey(identifier: String) = "$PREFIX:identifier:${normalize(identifier)}"

    private fun identifierLockKey(identifier: String) = "${identifierFailureKey(identifier)}:lock"

    private fun ipFailureKey(ip: String) = "$PREFIX:ip:$ip"

    private fun ipLockKey(ip: String) = "${ipFailureKey(ip)}:lock"
}

/** 已锁定：登录返回 429，带 Retry-After。 */
class LoginRateLimitedException(
    val retryAfterSeconds: Long,
) : AuthenticationException("登录尝试过于频繁")

/** Redis 不可用：登录 fail-closed 返回 503。 */
class LoginRateLimitUnavailableException(
    cause: Throwable,
) : AuthenticationException("登录限流服务不可用", cause)
