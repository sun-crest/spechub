package io.spechub.server.web

import io.spechub.server.repository.UserTokenEntity
import io.spechub.server.service.AuditService
import io.spechub.server.service.OrgService
import io.spechub.server.service.UserService
import io.spechub.server.service.UserTokenService
import io.spechub.server.web.common.ApiErrorBody
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.UserRecord
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * C4：用户 API 令牌。
 * - `GET/POST /api/auth/tokens`、`DELETE /api/auth/tokens/{id}`：自己的令牌；
 * - `GET /api/auth/users/{id}/tokens` / `DELETE .../{tokenId}`：org_admin 运维；
 * - 明文只返回一次；列表不返回 hash / 明文。
 */
@RestController
@RequestMapping("/api/auth")
class UserTokenController(
    private val auth: AuthService,
    private val users: UserService,
    private val orgs: OrgService,
    private val tokens: UserTokenService,
    private val audit: AuditService,
) {
    data class TokenView(
        val id: Long,
        val name: String,
        val prefix: String,
        val purpose: String,
        val expiresAt: String? = null,
        val lastUsedAt: String? = null,
        val createdAt: String? = null,
        val status: String,
    )

    data class CreateTokenRequest(
        @field:NotBlank val name: String,
        val expiresInDays: Long? = null,
    )

    data class CreateTokenResponse(
        val token: TokenView,
        val secret: String,
    )

    @GetMapping("/tokens")
    fun list(request: HttpServletRequest): List<TokenView> {
        val user = auth.requireHuman(request, "查看令牌")
        return userTokens(user).map(::toView)
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        request: HttpServletRequest,
        @RequestBody @Valid body: CreateTokenRequest,
    ): CreateTokenResponse {
        val user = auth.requireHuman(request, "创建令牌")
        val userId = checkNotNull(user.id) { "当前用户必有 id" }
        val days = body.expiresInDays ?: 90
        if (days !in 1..365) {
            throw ApiException(
                ApiErrorCode.VALIDATION,
                "expiresInDays ∈ 1..365",
                listOf(ApiErrorBody.FieldError("expiresInDays", "expiresInDays ∈ 1..365")),
            )
        }
        val issued =
            tokens.issue(
                userId = userId,
                name = body.name.trim(),
                purpose = UserTokenService.PURPOSE_API,
                expiresAt = Instant.now().plus(Duration.ofDays(days)),
            )
        audit.recordCurrent(
            auth.currentOrgId(request),
            "token.create",
            "user_token",
            issued.entity.id.toString(),
            "创建令牌 ${body.name.trim()}（$days 天）",
        )
        return CreateTokenResponse(toView(issued.entity), issued.raw)
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        request: HttpServletRequest,
        @PathVariable id: Long,
    ) {
        val user = auth.requireHuman(request, "吊销令牌")
        val userId = checkNotNull(user.id) { "当前用户必有 id" }
        if (!tokens.revoke(id, userId)) throw ApiException(ApiErrorCode.NOT_FOUND, "令牌 $id 不存在")
        audit.recordCurrent(auth.currentOrgId(request), "token.revoke", "user_token", id.toString(), "吊销自己的令牌")
    }

    @GetMapping("/users/{id}/tokens")
    fun adminList(
        request: HttpServletRequest,
        @PathVariable id: Long,
    ): List<TokenView> {
        auth.requireOrgAdmin(request, "查看用户令牌")
        requireTarget(request, id)
        return tokens.list(id).map(::toView)
    }

    @DeleteMapping("/users/{id}/tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun adminRevoke(
        request: HttpServletRequest,
        @PathVariable id: Long,
        @PathVariable tokenId: Long,
    ) {
        auth.requireOrgAdmin(request, "吊销用户令牌")
        requireTarget(request, id)
        val token = tokens.findById(tokenId)
        if (token == null || token.userId != id) throw ApiException(ApiErrorCode.NOT_FOUND, "令牌 $tokenId 不存在")
        tokens.revokeById(tokenId)
        audit.recordCurrent(
            auth.currentOrgId(request),
            "token.admin.revoke",
            "user_token",
            tokenId.toString(),
            "管理员吊销用户 $id 的令牌",
        )
    }

    private fun userTokens(user: UserRecord): List<UserTokenEntity> = tokens.list(checkNotNull(user.id) { "当前用户必有 id" })

    private fun requireTarget(
        request: HttpServletRequest,
        userId: Long,
    ): UserRecord {
        val target = users.findById(userId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        val orgId = auth.currentOrgId(request)
        if (orgs.membership(userId, orgId) == null) throw ApiException(ApiErrorCode.NOT_FOUND, "用户 $userId 不存在")
        return target
    }

    private fun toView(token: UserTokenEntity): TokenView =
        TokenView(
            id = checkNotNull(token.id) { "已持久化令牌必有 id" },
            name = token.name,
            prefix = token.tokenPrefix,
            purpose = token.purpose,
            expiresAt = token.expiresAt?.toString(),
            lastUsedAt = token.lastUsedAt?.toString(),
            createdAt = token.createdAt.toString(),
            status = tokens.status(token),
        )
}
