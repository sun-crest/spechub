package io.spechub.server.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/*
 * R9 —— 请求访问日志（规范 §6.3）。
 *
 * 每个 HTTP 请求一条 INFO：`method path status durationMs`。
 * 纪律：不记请求体/响应体/Authorization/query string，避免 token 与业务内容泄漏；
 * 使用 requestURI（不含 query）且只记方法/路径/状态/耗时。
 *
 * 顺序：先于 TokenAuthFilter 执行，确保被 401/403 拒绝的请求也有一条访问日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLogFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        req: HttpServletRequest,
        res: HttpServletResponse,
        chain: FilterChain,
    ) {
        val start = System.nanoTime()
        try {
            chain.doFilter(req, res)
        } finally {
            val durationMs = (System.nanoTime() - start) / 1_000_000
            log.info("{} {} {} {}ms", req.method, req.requestURI, res.status, durationMs)
        }
    }
}
