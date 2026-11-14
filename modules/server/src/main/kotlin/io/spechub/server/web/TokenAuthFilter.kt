package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/*
 * 可选的 Bearer token 认证（F3「写操作需确认」的服务端基础 + 供 MCP/CLI 复用）。
 * 仅在配置了 `spechub.admin-token`（环境 SPECHUB_ADMIN_TOKEN）时生效；
 * 未配置 → 全放行（P0 最小部署默认，避免给健康检查/本地开发加门槛）。
 * actuator 端点放行（compose healthcheck 无 token）。
 */

@Component
class TokenAuthFilter(private val props: SpecHubProperties) : OncePerRequestFilter() {

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val expected = props.adminToken
        if (expected == null || req.requestURI.startsWith("/actuator/")) {
            chain.doFilter(req, res)
            return
        }
        val auth = req.getHeader("Authorization")
        if (auth == "Bearer $expected") {
            chain.doFilter(req, res)
        } else {
            res.status = HttpStatus.UNAUTHORIZED.value()
            res.contentType = "application/json"
            res.writer.write("""{"error":"unauthorized"}""")
        }
    }
}