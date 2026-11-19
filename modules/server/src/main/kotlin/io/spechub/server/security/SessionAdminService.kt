package io.spechub.server.security

import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.stereotype.Service

/**
 * Spring Session 管理（C3 改密吊销其它会话；C4 设备列表 / 禁用 / 重置复用）。
 * 会话按 `PRINCIPAL_NAME_INDEX_NAME`（本地 username）索引。
 */
@Service
class SessionAdminService(
    private val sessions: FindByIndexNameSessionRepository<out Session>,
) {
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
}
