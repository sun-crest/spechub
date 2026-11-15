package io.spechub.server.web

import io.spechub.server.config.SpecHubProperties
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import jakarta.servlet.http.HttpServletRequest

/*
 * H6 —— admin token 简单登录占位（对齐 §7 O3：不做 OIDC，P2 I4 一起换 RBAC）：
 * - `GET  /api/auth/status`：报告后端是否启用鉴权（配了 spechub.admin-token）+ 当前请求是否已认证。
 * - `POST /api/auth/login`：校验登录表单提交的 admin token。
 * 无状态（token 就是口令，不存在 session / 不落库）；前端 localStorage 持有 token，请求带 Bearer。
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(private val props: SpecHubProperties) {

    data class LoginRequest(val token: String)

    data class StatusResponse(val authEnabled: Boolean, val authenticated: Boolean)

    @GetMapping("/status")
    fun status(req: HttpServletRequest): StatusResponse {
        val expected = props.adminToken
        val authenticated = expected != null && req.getHeader("Authorization") == "Bearer $expected"
        return StatusResponse(expected != null, authenticated)
    }

    @PostMapping("/login")
    fun login(@RequestBody body: LoginRequest): StatusResponse {
        val expected = props.adminToken
        if (expected == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "后端未启用鉴权（未配 spechub.admin-token），无需登录")
        }
        if (body.token != expected) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "令牌无效")
        }
        return StatusResponse(true, true)
    }
}