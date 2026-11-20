package io.spechub.server.service

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.repository.UserEntity
import io.spechub.server.repository.UserIdentityEntity
import io.spechub.server.repository.UserIdentityRepository
import io.spechub.server.repository.UserRepository
import io.spechub.server.repository.UserStatus
import io.spechub.server.security.OidcAppUserPrincipal
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * C6：OIDC 登录解析（不做会话 / HTTP）。
 *
 * 顺序：
 * 1. 校验 issuer / subject；require-verified-email=true 时拒绝未验证邮箱；
 * 2. `user_identity(oidc:<issuer>, sub)` 命中 → 状态校验；
 * 3. 未绑定 → 按 lower(email) 找已有用户并绑定（不覆盖密码）；
 * 4. 仍未找到 → auto-provision=false 拒绝；true 时建号（邮箱 local part，冲突追加序号），
 *    `org_member`、status=active、password_hash=NULL；
 * 5. 返回可序列化 [OidcAppUserPrincipal]；不创建会话（由 `oauth2Login` + Spring Session 完成）。
 */
@Service
class OidcLoginService(
    private val props: SpecHubProperties,
    private val users: UserRepository,
    private val identities: UserIdentityRepository,
    private val orgs: OrgService,
    private val audit: AuditService,
) {
    data class OidcClaims(
        val issuer: String,
        val subject: String,
        val email: String?,
        val emailVerified: Boolean,
        val name: String?,
        val claims: Map<String, Any> = emptyMap(),
        val idToken: OidcIdToken,
    )

    companion object {
        fun providerFor(issuer: String): String = "oidc:${issuer.trimEnd('/')}"
    }

    @Transactional
    fun resolve(claims: OidcClaims): OidcAppUserPrincipal {
        if (claims.issuer.isBlank() || claims.subject.isBlank()) {
            throw OidcLoginException("OIDC issuer / subject 缺失")
        }
        if (props.oidc.requireVerifiedEmail && !claims.emailVerified) {
            throw OidcLoginException("OIDC 邮箱未验证")
        }
        val provider = providerFor(claims.issuer)
        val email =
            claims
                .email
                ?.trim()
                ?.lowercase()
                ?.ifBlank { null }

        val bound = identities.findByProviderAndSubject(provider, claims.subject)
        if (bound != null) {
            val user = requireLoginUser(bound.userId)
            auditOidc("auth.oidc.login", user, "OIDC 登录成功（已绑定身份）")
            return principal(user, claims)
        }

        val existing = email?.let { users.findByEmail(it) }
        if (existing != null) {
            val boundUser = bindOrLoad(existing, provider, claims.subject, email)
            auditOidc("auth.oidc.link", boundUser, "OIDC 身份绑定到既有用户")
            auditOidc("auth.oidc.login", boundUser, "OIDC 登录成功（邮箱绑定）")
            return principal(boundUser, claims)
        }

        if (!props.oidc.autoProvision) {
            throw OidcLoginException("OIDC 账号未开通（auto-provision 已关闭）")
        }
        if (email == null) {
            throw OidcLoginException("OIDC 未返回邮箱，无法自动建号")
        }
        val created = provision(email, claims)
        val boundUser = bindOrLoad(created, provider, claims.subject, email)
        auditOidc("auth.oidc.provision", boundUser, "OIDC 自动建号 ${boundUser.username}")
        auditOidc("auth.oidc.login", boundUser, "OIDC 登录成功（自动建号）")
        return principal(boundUser, claims)
    }

    private fun provision(
        email: String,
        claims: OidcClaims,
    ): UserEntity {
        val org = orgs.findBySlug(props.oidc.defaultOrgSlug) ?: orgs.defaultOrg()
        val orgId = checkNotNull(org.id) { "org 必有 id" }
        val username = uniqueUsername(email.substringBefore("@"))
        val saved =
            users.save(
                UserEntity(
                    username = username,
                    displayName = claims.name?.takeIf { it.isNotBlank() } ?: username,
                    email = email,
                    passwordHash = null,
                    status = UserStatus.ACTIVE,
                    createdAt = Instant.now(),
                ),
            )
        val userId = checkNotNull(saved.id) { "用户创建后应回填 id" }
        orgs.ensureMembership(userId, orgId, OrgService.ROLE_MEMBER)
        return saved
    }

    /** 绑定身份；并发下唯一键冲突则回读已绑定用户。 */
    private fun bindOrLoad(
        user: UserEntity,
        provider: String,
        subject: String,
        email: String?,
    ): UserEntity {
        val userId = checkNotNull(user.id) { "已持久化用户必有 id" }
        val existing = identities.findByProviderAndSubject(provider, subject)
        if (existing != null) return requireLoginUser(existing.userId)
        return try {
            identities.save(
                UserIdentityEntity(
                    userId = userId,
                    provider = provider,
                    subject = subject,
                    email = email,
                    createdAt = Instant.now(),
                ),
            )
            user
        } catch (e: DataIntegrityViolationException) {
            val raced =
                identities.findByProviderAndSubject(provider, subject)
                    ?: throw OidcLoginException("OIDC 身份绑定冲突")
            requireLoginUser(raced.userId)
        }
    }

    private fun requireLoginUser(userId: Long): UserEntity {
        val user = users.findById(userId).orElse(null) ?: throw OidcLoginException("绑定用户不存在")
        if (user.status !in UserStatus.LOGINNABLE) throw OidcLoginException("账号不可用")
        return user
    }

    private fun auditOidc(
        action: String,
        user: UserEntity,
        detail: String,
    ) {
        audit.recordAuth(
            audit.defaultOrgId(),
            AuditService.AuditActor(
                checkNotNull(user.id) { "已持久化用户必有 id" },
                user.username,
                AuditService.ACTOR_USER,
            ),
            action,
            "user",
            user.id?.toString(),
            detail,
            AuditService.OUTCOME_SUCCESS,
            audit.currentRequest(),
        )
    }

    private fun uniqueUsername(rawBase: String): String {
        val base =
            rawBase
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "oidc-user" }
        var candidate = base
        var suffix = 2
        while (users.findByUsername(candidate) != null) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
    }

    private fun principal(
        user: UserEntity,
        claims: OidcClaims,
    ): OidcAppUserPrincipal =
        OidcAppUserPrincipal(
            appUserId = checkNotNull(user.id) { "已持久化用户必有 id" },
            username = user.username,
            displayName = user.displayName,
            claims = claims.claims,
            idToken = claims.idToken,
        )
}

/** OIDC 业务拒绝（账号未开通 / 邮箱未验证 / 禁用等）；由 Web 失败 handler 统一重定向。 */
class OidcLoginException(
    message: String,
) : RuntimeException(message)
