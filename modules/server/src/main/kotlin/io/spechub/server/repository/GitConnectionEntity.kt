package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `git_connection`：Git 提供方账号/安装。阶段 A 只建表，外部 provider 连接器留 D1。 */
@Table("git_connection")
data class GitConnectionEntity(
    @Id val id: Long? = null,
    @Column("org_id") val orgId: Long,
    val provider: String,
    @Column("external_account_id") val externalAccountId: String? = null,
    @Column("display_name") val displayName: String = "",
    @Column("credentials_enc") val credentialsEnc: String? = null,
    @Column("webhook_secret_enc") val webhookSecretEnc: String? = null,
    val status: String = "active",
    @Column("created_at") val createdAt: Instant,
)
