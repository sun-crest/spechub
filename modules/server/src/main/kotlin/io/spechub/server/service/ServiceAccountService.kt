package io.spechub.server.service

import io.spechub.server.repository.ProjectRepository
import io.spechub.server.repository.ServiceAccountEntity
import io.spechub.server.repository.ServiceAccountRepository
import io.spechub.server.repository.ServiceAccountStatus
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.ServiceAccountRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 项目级服务账号（C5）。
 *
 * - 只存 SHA-256 hash 与前缀；明文只在创建 / 轮换时返回一次。
 * - 角色仅 viewer / editor；过期必须 1–365 天。
 * - `rotate` 在同一行内替换 hash（旧密钥立即失效）；`revoke` 置 revoked 立即生效。
 * - 服务账号不进入 `membership` / `org_membership`，不可登录 Web。
 */
@Service
class ServiceAccountService(
    private val accounts: ServiceAccountRepository,
    private val projects: ProjectRepository,
    private val audit: AuditService,
) {
    companion object {
        const val ROLE_VIEWER = "viewer"
        const val ROLE_EDITOR = "editor"
        val ROLES = setOf(ROLE_VIEWER, ROLE_EDITOR)
        const val PREFIX = UserTokenService.PREFIX_SERVICE

        private val MAX_EXPIRY_DAYS = 365L
        private val DEFAULT_EXPIRY_DAYS = 90L
    }

    data class Issued(
        val account: ServiceAccountRecord,
        val token: String,
    )

    /** 创建服务账号；`expiresAt` 必须在 (now, now+365d] 内。 */
    @Transactional
    fun create(
        orgId: Long,
        projectId: Long,
        name: String,
        role: String,
        expiresAt: Instant,
    ): Issued {
        val project =
            projects.findById(projectId).orElse(null)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        if (project.orgId != orgId) {
            throw ApiException(ApiErrorCode.NOT_FOUND, "project $projectId 不存在")
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw ApiException(ApiErrorCode.VALIDATION, "服务账号名称不能为空")
        validateRole(role)
        validateExpiry(expiresAt)
        if (accounts.findAllByProjectIdOrderByNameAsc(projectId).any { it.name.equals(trimmedName, ignoreCase = true) }) {
            throw ApiException(ApiErrorCode.CONFLICT, "服务账号 $trimmedName 已存在")
        }

        val raw = UserTokenService.generateRaw(PREFIX)
        val saved =
            accounts.save(
                ServiceAccountEntity(
                    orgId = orgId,
                    projectId = projectId,
                    name = trimmedName,
                    tokenPrefix = UserTokenService.displayPrefix(raw),
                    tokenHash = UserTokenService.sha256(raw),
                    role = role,
                    status = ServiceAccountStatus.ACTIVE,
                    expiresAt = expiresAt,
                    createdAt = Instant.now(),
                ),
            )
        audit.record(
            orgId,
            audit.currentActor(),
            "service_account.create",
            "service_account",
            saved.id?.toString(),
            "创建服务账号 ${saved.name}（${saved.role}）",
        )
        return Issued(saved.toRecord(), raw)
    }

    fun list(projectId: Long): List<ServiceAccountRecord> = accounts.findAllByProjectIdOrderByNameAsc(projectId).map { it.toRecord() }

    fun find(id: Long): ServiceAccountRecord? = accounts.findById(id).orElse(null)?.toRecord()

    @Transactional
    fun revoke(
        id: Long,
        projectId: Long,
    ): ServiceAccountRecord {
        val account =
            accounts.findByIdAndProjectId(id, projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "服务账号 $id 不存在")
        val saved =
            if (account.status == ServiceAccountStatus.REVOKED) {
                account
            } else {
                accounts.save(account.copy(status = ServiceAccountStatus.REVOKED, revokedAt = Instant.now()))
            }
        audit.record(
            account.orgId,
            audit.currentActor(),
            "service_account.revoke",
            "service_account",
            id.toString(),
            "吊销服务账号 ${account.name}",
        )
        return saved.toRecord()
    }

    /** 轮换：同一行替换 hash / 前缀 / 过期时间；旧密钥立即失效，新明文只返回一次。 */
    @Transactional
    fun rotate(
        id: Long,
        projectId: Long,
        expiresAt: Instant?,
    ): Issued {
        val account =
            accounts.findByIdAndProjectId(id, projectId)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "服务账号 $id 不存在")
        if (account.status == ServiceAccountStatus.REVOKED) {
            throw ApiException(ApiErrorCode.CONFLICT, "服务账号 $id 已吊销，不能轮换")
        }
        val newExpiry =
            expiresAt
                ?: account.expiresAt.takeIf { it.isAfter(Instant.now()) }
                ?: Instant.now().plus(Duration.ofDays(DEFAULT_EXPIRY_DAYS))
        validateExpiry(newExpiry)

        val raw = UserTokenService.generateRaw(PREFIX)
        val saved =
            accounts.save(
                account.copy(
                    tokenPrefix = UserTokenService.displayPrefix(raw),
                    tokenHash = UserTokenService.sha256(raw),
                    expiresAt = newExpiry,
                    lastUsedAt = null,
                ),
            )
        audit.record(
            account.orgId,
            audit.currentActor(),
            "service_account.rotate",
            "service_account",
            id.toString(),
            "轮换服务账号 ${account.name} 密钥",
        )
        return Issued(saved.toRecord(), raw)
    }

    /** Bearer 解析（provider 当前直接查库；保留服务层入口供测试 / 后续复用）。 */
    fun resolve(raw: String): ServiceAccountRecord? {
        val account = accounts.findByTokenHash(UserTokenService.sha256(raw)) ?: return null
        val now = Instant.now()
        if (account.status != ServiceAccountStatus.ACTIVE) return null
        if (account.revokedAt != null) return null
        if (!account.expiresAt.isAfter(now)) return null
        return account.toRecord()
    }

    fun defaultExpiry(days: Long = DEFAULT_EXPIRY_DAYS): Instant {
        validateDays(days)
        return Instant.now().plus(Duration.ofDays(days))
    }

    fun validateDays(days: Long) {
        if (days !in 1..MAX_EXPIRY_DAYS) {
            throw ApiException(ApiErrorCode.VALIDATION, "expiresInDays ∈ 1..$MAX_EXPIRY_DAYS")
        }
    }

    private fun validateRole(role: String) {
        if (role !in ROLES) {
            throw ApiException(ApiErrorCode.VALIDATION, "role ∈ $ROLES")
        }
    }

    private fun validateExpiry(expiresAt: Instant) {
        val now = Instant.now()
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(Duration.ofDays(MAX_EXPIRY_DAYS)))) {
            throw ApiException(ApiErrorCode.VALIDATION, "服务账号过期时间必须在 1–$MAX_EXPIRY_DAYS 天内")
        }
    }

    private fun ServiceAccountEntity.toRecord(): ServiceAccountRecord =
        ServiceAccountRecord(
            id = id,
            orgId = orgId,
            projectId = projectId,
            name = name,
            prefix = tokenPrefix,
            role = role,
            status = status,
            expiresAt = expiresAt.toString(),
            lastUsedAt = lastUsedAt?.toString(),
            revokedAt = revokedAt?.toString(),
            createdAt = createdAt.toString(),
        )
}
