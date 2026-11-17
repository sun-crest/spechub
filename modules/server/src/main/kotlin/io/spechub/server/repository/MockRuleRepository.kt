package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface MockRuleRepository : CrudRepository<MockRuleEntity, Long> {

    @Query("SELECT * FROM mock_rule WHERE repo_id = :repoId ORDER BY id")
    fun findByRepoId(repoId: Long): List<MockRuleEntity>

    /** 幂等键匹配：接口级（api_id 非空） */
    @Query(
        """
        SELECT * FROM mock_rule
        WHERE repo_id = :repoId AND field_pattern = :fieldPattern AND rule_type = :ruleType AND api_id = :apiId
        """,
    )
    fun findMatchingWithApi(repoId: Long, apiId: Long, fieldPattern: String, ruleType: String): MockRuleEntity?

    /** 幂等键匹配：项目级（api_id 为空） */
    @Query(
        """
        SELECT * FROM mock_rule
        WHERE repo_id = :repoId AND field_pattern = :fieldPattern AND rule_type = :ruleType AND api_id IS NULL
        """,
    )
    fun findProjectLevel(repoId: Long, fieldPattern: String, ruleType: String): MockRuleEntity?
}
