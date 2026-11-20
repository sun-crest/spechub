package io.spechub.server.service

import io.spechub.server.repository.GitConnectionEntity
import io.spechub.server.repository.GitConnectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Git 接入 Service（阶段 A 骨架）。
 *
 * 阶段 A 只创建 managed/local 等无密钥连接；GitHub App / GitLab OAuth 凭据的
 * 加密落库与 webhook secret 由 D1 的 KMS 信封加密实现补齐（§5.2）。
 */
@Service
class GitConnectionService(
    private val connections: GitConnectionRepository,
    private val audit: AuditService,
) {
    companion object {
        val PROVIDERS = setOf("managed", "local", "github", "gitlab", "gitea", "ssh")
    }

    fun list(orgId: Long): List<GitConnectionEntity> = connections.findAllByOrgIdOrderByIdAsc(orgId)

    fun get(id: Long): GitConnectionEntity? = connections.findById(id).orElse(null)

    @Transactional
    fun create(
        orgId: Long,
        provider: String,
        displayName: String = "",
        externalAccountId: String? = null,
    ): GitConnectionEntity {
        require(PROVIDERS.contains(provider)) { "provider ∈ $PROVIDERS" }
        val saved =
            connections.save(
                GitConnectionEntity(
                    orgId = orgId,
                    provider = provider,
                    externalAccountId = externalAccountId,
                    displayName = displayName.trim(),
                    createdAt = Instant.now(),
                ),
            )
        audit.record(
            orgId,
            audit.currentActor(),
            "git_connection.create",
            "git_connection",
            saved.id?.toString(),
            "创建 Git 连接 ${saved.provider} ${saved.displayName}",
        )
        return saved
    }

    @Transactional
    fun disable(id: Long): GitConnectionEntity {
        val connection = connections.findById(id).orElse(null) ?: throw IllegalArgumentException("git connection $id 不存在")
        val saved = if (connection.status == "disabled") connection else connections.save(connection.copy(status = "disabled"))
        audit.record(
            saved.orgId,
            audit.currentActor(),
            "git_connection.disable",
            "git_connection",
            id.toString(),
            "禁用 Git 连接 ${saved.provider} ${saved.displayName}",
        )
        return saved
    }
}
