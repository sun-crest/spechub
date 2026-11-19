package io.spechub.server.service

import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserRepository
import io.spechub.server.repository.UserStatus
import io.spechub.server.repository.UserTokenEntity
import io.spechub.server.repository.UserTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * 用户 API 令牌（阶段 C）。
 *
 * - 明文只返回一次；库中只存 SHA-256 hash 与前缀（列表展示 / secret 扫描识别）。
 * - `legacy` 无过期（旧 admin-token / app_user.token_hash 迁移）；`api` / `invite` /
 *   `password_reset` 必须有过期时间。
 * - `resolveLogin` 只接受 `legacy` / `api`；邀请 / 重置令牌仅用于密码设置，不能登录。
 * - `last_used_at` 写节流 5 分钟，避免每请求写放大。
 */
@Service
class UserTokenService(
    private val tokens: UserTokenRepository,
    private val users: UserRepository,
) {
    companion object {
        const val PREFIX_USER = "shu_"
        const val PREFIX_SERVICE = "shs_"
        const val PURPOSE_LEGACY = "legacy"
        const val PURPOSE_API = "api"
        const val PURPOSE_INVITE = "invite"
        const val PURPOSE_PASSWORD_RESET = "password_reset"

        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(5)
        private val LOGIN_PURPOSES = setOf(PURPOSE_LEGACY, PURPOSE_API)

        /** 令牌 / 密码统一使用 SHA-256（十六进制小写）。 */
        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 生成 `shu_` / `shs_` 前缀的 opaque token（48 位十六进制随机）。 */
        fun generateRaw(prefix: String = PREFIX_USER): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return prefix + bytes.joinToString("") { "%02x".format(it) }
        }

        /** 列表展示用前缀（足够识别，不足以还原明文）。 */
        fun displayPrefix(raw: String): String = raw.takeWhile { it != '_' } + "_" + raw.dropWhile { it != '_' }.drop(1).take(8)
    }

    data class Issued(
        val raw: String,
        val entity: UserTokenEntity,
    )

    data class ResolvedUser(
        val token: UserTokenEntity,
        val user: UserEntity,
    )

    /** 签发令牌；`rawToken` 仅用于旧 admin-token / 测试固定值，正常路径随机生成。 */
    @Transactional
    fun issue(
        userId: Long,
        name: String,
        purpose: String,
        expiresAt: Instant?,
        prefix: String = PREFIX_USER,
        rawToken: String? = null,
    ): Issued {
        require(purpose == PURPOSE_LEGACY || expiresAt != null) { "非 legacy 令牌必须有过期时间" }
        val raw = rawToken ?: generateRaw(prefix)
        val saved =
            tokens.save(
                UserTokenEntity(
                    userId = userId,
                    name = name,
                    tokenPrefix = if (raw.startsWith(PREFIX_USER) || raw.startsWith(PREFIX_SERVICE)) displayPrefix(raw) else "",
                    tokenHash = sha256(raw),
                    purpose = purpose,
                    expiresAt = expiresAt,
                    createdAt = Instant.now(),
                ),
            )
        return Issued(raw, saved)
    }

    /** bootstrap：用当前 admin-token 替换该用户的 legacy 行（旧值立即失效，不恢复）。 */
    @Transactional
    fun replaceLegacy(
        userId: Long,
        rawToken: String,
    ) {
        tokens.deleteAllByUserIdAndPurpose(userId, PURPOSE_LEGACY)
        issue(userId, "legacy", PURPOSE_LEGACY, null, prefix = "", rawToken = rawToken)
    }

    /** 登录 / Bearer：只接受 legacy / api，且未过期、未吊销、用户可登录。 */
    @Transactional
    fun resolveLogin(raw: String): ResolvedUser? {
        val token = tokens.findByTokenHash(sha256(raw)) ?: return null
        if (token.purpose !in LOGIN_PURPOSES) return null
        return validate(token)
    }

    /** 密码设置：invite / password_reset，且必须未使用。 */
    @Transactional
    fun resolveSetup(raw: String): ResolvedUser? {
        val token = tokens.findByTokenHash(sha256(raw)) ?: return null
        if (token.purpose != PURPOSE_INVITE && token.purpose != PURPOSE_PASSWORD_RESET) return null
        if (token.usedAt != null) return null
        return validate(token)
    }

    fun list(userId: Long): List<UserTokenEntity> = tokens.findAllByUserIdOrderByCreatedAtDesc(userId)

    fun findOwned(
        id: Long,
        userId: Long,
    ): UserTokenEntity? = tokens.findByIdAndUserId(id, userId)

    @Transactional
    fun revoke(
        id: Long,
        userId: Long,
    ): Boolean {
        val token = tokens.findByIdAndUserId(id, userId) ?: return false
        if (token.revokedAt != null) return true
        tokens.save(token.copy(revokedAt = Instant.now()))
        return true
    }

    @Transactional
    fun revokeAll(userId: Long) {
        tokens.findAllByUserIdOrderByCreatedAtDesc(userId).forEach { token ->
            if (token.revokedAt == null) tokens.save(token.copy(revokedAt = Instant.now()))
        }
    }

    /** 轮换：吊销旧令牌并签发同名 / 同用途 / 同过期时间的新令牌（旧密钥立即失效）。 */
    @Transactional
    fun rotate(
        id: Long,
        userId: Long,
    ): Issued? {
        val old = tokens.findByIdAndUserId(id, userId) ?: return null
        if (old.revokedAt == null) tokens.save(old.copy(revokedAt = Instant.now()))
        val prefix = if (old.purpose == PURPOSE_LEGACY) "" else PREFIX_USER
        return issue(userId, old.name, old.purpose, old.expiresAt, prefix = prefix)
    }

    @Transactional
    fun markUsed(id: Long) {
        val token = tokens.findById(id).orElse(null) ?: return
        if (token.usedAt == null) tokens.save(token.copy(usedAt = Instant.now(), lastUsedAt = Instant.now()))
    }

    private fun validate(token: UserTokenEntity): ResolvedUser? {
        val now = Instant.now()
        if (token.revokedAt != null) return null
        if (token.expiresAt != null && !token.expiresAt.isAfter(now)) return null
        val user = users.findById(token.userId).orElse(null) ?: return null
        if (user.status !in UserStatus.LOGINNABLE) return null
        val used =
            if (token.lastUsedAt == null || token.lastUsedAt.isBefore(now.minus(TOUCH_INTERVAL))) {
                // 节流写入；失败不影响认证（令牌本身仍有效）。
                tokens.save(token.copy(lastUsedAt = now))
            } else {
                token
            }
        return ResolvedUser(used, user)
    }
}
