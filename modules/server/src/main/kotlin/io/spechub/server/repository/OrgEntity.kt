package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `org`（阶段 A 单组织；SaaS 多组织共用同一模型）。 */
@Table("org")
data class OrgEntity(
    @Id val id: Long? = null,
    val name: String,
    val slug: String,
    val status: String = "active",
    val plan: String? = null,
    @Column("trial_ends_at") val trialEndsAt: Instant? = null,
    @Column("quota_json") val quotaJson: String? = null,
    @Column("suspended_reason") val suspendedReason: String? = null,
    @Column("created_at") val createdAt: Instant,
)
