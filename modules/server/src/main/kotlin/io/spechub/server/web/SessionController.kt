package io.spechub.server.web

import io.spechub.server.security.SessionAdminService
import io.spechub.server.service.AuditService
import io.spechub.server.service.OrgService
import io.spechub.server.service.UserService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * C4：活动会话（Spring Session Redis）。
 * - 普通用户：列出 / 吊销自己的会话（脱敏 id，不返回主键与序列化属性）。
 * - org_admin：查看 / 吊销指定用户会话。
 */
@RestController
@RequestMapping("/api/auth")
class SessionController(
    private val auth: AuthService,
    private val sessions: SessionAdminService,
    private val users: UserService,
    private val orgs: OrgService,
    private val audit: AuditService,
) {
    @GetMapping("/sessions")
    fun list(request: HttpServletRequest): List<SessionAdminService.SessionView> {
        val user = auth.requireHuman(request, "查看活动会话")
        return sessions.list(user.username, request.getSession(false)?.id)
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        request: HttpServletRequest,
        @PathVariable id: String,
    ) {
        val user = auth.requireHuman(request, "吊销活动会话")
        val sessionId =
            sessions.find(user.username, id)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "会话不存在")
        sessions.delete(sessionId)
        audit.recordCurrent(auth.currentOrgId(request), "session.revoke", "session", id, "吊销自己的活动会话")
    }

    @GetMapping("/users/{id}/sessions")
    fun userSessions(
        request: HttpServletRequest,
        @PathVariable id: Long,
    ): List<SessionAdminService.SessionView> {
        auth.requireOrgAdmin(request, "查看用户会话")
        val target = requireTarget(request, id)
        return sessions.list(target.username, currentSessionId = null)
    }

    @DeleteMapping("/users/{id}/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeUserSession(
        request: HttpServletRequest,
        @PathVariable id: Long,
        @PathVariable sessionId: String,
    ) {
        auth.requireOrgAdmin(request, "吊销用户会话")
        val target = requireTarget(request, id)
        val fullId =
            sessions.find(target.username, sessionId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "会话不存在")
        sessions.delete(fullId)
        audit.recordCurrent(auth.currentOrgId(request), "session.admin.revoke", "user", id.toString(), "管理员吊销用户 $id 的会话")
    }

    private fun requireTarget(
        request: HttpServletRequest,
        userId: Long,
    ): UserRecord {
        val target = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        val orgId = auth.currentOrgId(request)
        if (orgs.membership(userId, orgId) == null) throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        return target
    }
}
