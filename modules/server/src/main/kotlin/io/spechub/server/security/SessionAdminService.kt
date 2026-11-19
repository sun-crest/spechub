package io.spechub.server.security

import io.spechub.server.service.UserTokenService
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service

/**
 * Spring Session 管理（C3 改密吊销；C4 设备列表 / 管理员吊销 / 禁用 / 重置复用）。
 *
 * 会话按 `PRINCIPAL_NAME_INDEX_NAME`（本地 username）索引。对外只暴露脱敏 id
 * （`sha256(sessionId).take(16)`），不返回 Spring Session 主键 / 序列化属性。
 */
@Service
class SessionAdminService(
    private val sessions: FindByIndexNameSessionRepository<out Session>,
) {
    data class SessionView(
        val id: String,
        val ip: String?,
        val userAgent: String?,
        val createdAt: String?,
        val lastAccessedAt: String?,
        val current: Boolean,
    )

    fun list(
        username: String,
        currentSessionId: String?,
    ): List<SessionView> =
        sessions
            .findByPrincipalName(username)
            .map { (id, session) ->
                SessionView(
                    id = mask(id),
                    ip = session.getAttribute(SessionAttributes.IP) as? String,
                    userAgent = session.getAttribute(SessionAttributes.USER_AGENT) as? String,
                    createdAt = session.creationTime.toString(),
                    lastAccessedAt = session.lastAccessedTime.toString(),
                    current = id == currentSessionId,
                )
            }.sortedByDescending { it.createdAt }

    /** 按脱敏 id 定位用户会话；返回完整 sessionId 供删除。 */
    fun find(
        username: String,
        maskedId: String,
    ): String? =
        sessions
            .findByPrincipalName(username)
            .keys
            .firstOrNull { mask(it) == maskedId }

    fun delete(sessionId: String) {
        sessions.deleteById(sessionId)
    }

    /** 删除该用户的其它会话，保留当前会话（Bearer 改密时 keepSessionId 为空 → 删除全部 Web 会话）。 */
    fun deleteOtherSessions(
        username: String,
        keepSessionId: String?,
    ): Int {
        val ids = sessions.findByPrincipalName(username).keys.filter { it != keepSessionId }
        ids.forEach { sessions.deleteById(it) }
        return ids.size
    }

    fun deleteAllSessions(username: String): Int {
        val ids = sessions.findByPrincipalName(username).keys
        ids.forEach { sessions.deleteById(it) }
        return ids.size
    }

    fun sessionIds(username: String): Set<String> = sessions.findByPrincipalName(username).keys

    private fun mask(sessionId: String): String = UserTokenService.sha256(sessionId).take(16)
}
