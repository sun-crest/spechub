package io.spechub.server.repository

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 管理库 `mock_rule`：按字段路径生成 mock 值（api_id 为空 = 项目级规则）。 */
@Table("mock_rule")
data class MockRuleEntity(
    @Id val id: Long? = null,
    @Column("repo_id") val repoId: Long,
    @Column("api_id") val apiId: Long? = null,
    @Column("field_pattern") val fieldPattern: String,
    @Column("rule_type") val ruleType: String,
    @Column("rule_value") val ruleValue: String = "",
    @Column("created_at") val createdAt: Instant,
)
