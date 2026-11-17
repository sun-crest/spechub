package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/*
 * Bearer token 认证（F3「写操作需确认」服务端基础 → I4 RBAC 升级）。
 * - 仅配置 `spechub.admin-token` 时启用：token → AuthService 解析用户（SHA-256 查 app_user）。
 * - 未配置 → 全放行（P0 最小部署默认，本地开发 / compose 无门槛）。
 * - 鉴权成功把当前用户挂到 request attribute（AuthService.currentUser 读取）。
 * - 放行：/actuator（healthcheck 无 token）、/api/auth（登录/状态端点自身）、
 *   /mock 前缀（I3 真实 mock 服务：只读投影生成、联调方无平台凭据）；
 *   R11 文档端点在 `spechub.openapi-public=true`（dev）下放行，prod 关闭。
 */
@Component
class TokenAuthFilter(
    private val auth: AuthService,
    private val json: ObjectMapper,
    private val props: SpecHubProperties,
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
            req.setAttribute(AuthService.REQ_ORG, auth.orgForUser(user))
            chain.doFilter(req, res)
            return
        }
        if (user == null && bearerToken(req) != null) {
            // 带了 token 但未命中用户（token 无效）
            reject(req, res, ApiErrorCode.AUTH_UNAUTHORIZED, "无效令牌")
            return
        }
        // 无 token 或未启用鉴权
        if (!auth.authEnabled ||
            req.requestURI.startsWith("/actuator/") ||
            req.requestURI.startsWith("/api/auth/") ||
            req.requestURI.startsWith("/mock/") ||
            (props.openapiPublic && isOpenApiPath(req.requestURI))
        ) {
            chain.doFilter(req, res)
        } else {
            reject(req, res, ApiErrorCode.AUTH_UNAUTHORIZED, "unauthorized")
        }
    }

    /** R11 文档端点：springdoc api-docs、Knife4j UI 入口与 webjars 静态资源。 */
    private fun isOpenApiPath(uri: String): Boolean =
        uri == "/doc.html" ||
            uri == "/v3/api-docs" ||
            uri.startsWith("/v3/api-docs/") ||
            uri.startsWith("/webjars/") ||
            uri.startsWith("/swagger-ui/")

    private fun reject(
        req: HttpServletRequest,
        res: HttpServletResponse,
        code: ApiErrorCode,
        msg: String,
    ) {
        res.status = code.status
        res.contentType = "application/json"
        res.writer.write(json.writeValueAsString(ApiErrorBody(code.status, code.name, msg, req.requestURI)))
    }

    private fun bearerToken(req: HttpServletRequest): String? {
        val h = req.getHeader("Authorization") ?: return null
        return if (h.startsWith("Bearer ")) h.removePrefix("Bearer ").trim().ifEmpty { null } else null
    }
}
