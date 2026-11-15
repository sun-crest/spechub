package io.spechub.server.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/*
 * Bearer token 认证（F3「写操作需确认」服务端基础 → I4 RBAC 升级）。
 * - 仅配置 `spechub.admin-token` 时启用：token → AuthService 解析用户（SHA-256 查 app_user）。
 * - 未配置 → 全放行（P0 最小部署默认，本地开发 / compose 无门槛）。
 * - 鉴权成功把当前用户挂到 request attribute（AuthService.currentUser 读取）。
 * - 放行：/actuator（healthcheck 无 token）、/api/auth（登录/状态端点自身）、
 *   /mock 前缀（I3 真实 mock 服务：只读投影生成、联调方无平台凭据）。
 */
@Component
class TokenAuthFilter(
    private val auth: AuthService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val user = auth.userForToken(bearerToken(req))
        if (user != null) {
            // 命中用户 → 挂到请求（即使未启用鉴权也挂：admin-token 兼容路径）
            req.setAttribute(AuthService.REQ_USER, user)
            req.setAttribute(AuthService.REQ_USERNAME, user.username)
            chain.doFilter(req, res)
            return
        }
        if (user == null && bearerToken(req) != null) {
            // 带了 token 但未命中用户（token 无效）
            reject(res, "无效令牌")
            return
        }
        // 无 token 或未启用鉴权
        if (!auth.authEnabled ||
            req.requestURI.startsWith("/actuator/") ||
            req.requestURI.startsWith("/api/auth/") ||
            req.requestURI.startsWith("/mock/")) {
            chain.doFilter(req, res)
        } else {
            reject(res, "unauthorized")
        }
    }

    private fun reject(res: HttpServletResponse, msg: String) {
        res.status = HttpStatus.UNAUTHORIZED.value()
        res.contentType = "application/json"
        res.writer.write("""{"error":"$msg"}""")
    }

    private fun bearerToken(req: HttpServletRequest): String? {
        val h = req.getHeader("Authorization") ?: return null
        return if (h.startsWith("Bearer ")) h.removePrefix("Bearer ").trim().ifEmpty { null } else null
    }
}