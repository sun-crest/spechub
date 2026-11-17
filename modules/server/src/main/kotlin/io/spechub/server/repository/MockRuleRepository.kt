package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface MockRuleRepository : CrudRepository<MockRuleEntity, Long> {

    @Query("SELECT * FROM mock_rule WHERE repo_id = :repoId ORDER BY id")
    fun findByRepoId(repoId: Long): List<MockRuleEntity>

    /**
     * 幂等键匹配（repo + api（可空）+ pattern + type）。
     * apiId 可为 null：用 `CAST(:apiId AS BIGINT) IS NULL` 兼容 PG / MySQL 的参数类型推断。
     */
    @Query(
        """
        SELECT * FROM mock_rule
        WHERE repo_id = :repoId AND field_pattern = :fieldPattern AND rule_type = :ruleType
          AND ((CAST(:apiId AS BIGINT) IS NULL AND api_id IS NULL) OR api_id = CAST(:apiId AS BIGINT))
        """,
    )
    fun findMatching(repoId: Long, apiId: Long?, fieldPattern: String, ruleType: String): MockRuleEntity?
}
