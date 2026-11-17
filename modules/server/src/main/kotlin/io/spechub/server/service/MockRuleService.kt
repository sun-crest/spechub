package io.spechub.server.service

import io.spechub.server.repository.MockRuleEntity
import io.spechub.server.repository.MockRuleRepository
import io.spechub.spec.index.MockRuleRecord
import io.spechub.spec.mock.MockRuleSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Mock 规则域 Service（Phase 2）：从投影库迁入管理库，并实现 mock 模块的 `MockRuleSource` 端口。
 *
 * - `upsert` 幂等键：repo + api（可空）+ field_pattern + rule_type（先查后写）。
 * - `rulesFor` 供 MockEngine 读取；mock 模块不依赖 server。
 */
@Service
class MockRuleService(
    private val rules: MockRuleRepository,
) : MockRuleSource {

    override fun rulesFor(repoId: Long): List<MockRuleRecord> = rules.findByRepoId(repoId).map { it.toRecord() }

    @Transactional
    fun upsert(rule: MockRuleRecord): MockRuleRecord {
        val existing = rules.findMatching(rule.repoId, rule.apiId, rule.fieldPattern, rule.ruleType)
        val saved = if (existing != null) {
            rules.save(existing.copy(ruleValue = rule.ruleValue))
        } else {
            rules.save(
                MockRuleEntity(
                    repoId = rule.repoId, apiId = rule.apiId, fieldPattern = rule.fieldPattern,
                    ruleType = rule.ruleType, ruleValue = rule.ruleValue, createdAt = Instant.now(),
                ),
            )
        }
        return saved.toRecord()
    }

    fun get(id: Long): MockRuleRecord? = rules.findById(id).orElse(null)?.toRecord()

    @Transactional
    fun delete(id: Long) {
        rules.deleteById(id)
    }

    private fun MockRuleEntity.toRecord(): MockRuleRecord = MockRuleRecord(
        id = id,
        repoId = repoId,
        apiId = apiId,
        fieldPattern = fieldPattern,
        ruleType = ruleType,
        ruleValue = ruleValue,
    )
}
