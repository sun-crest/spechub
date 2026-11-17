package io.spechub.server.web

import org.springframework.jdbc.core.JdbcTemplate

/**
 * 测试隔离标记基类：每个 @SpringBootTest 测试类在自身 companion 里
 * 用 @TempDir + @DynamicPropertySource 声明全新临时 gitsource/sqlite/artifacts，
 * 并通过 TestManagementDb 注册独立管理库 schema（共享 PG 容器 + 每类 schema）。
 * 彻底消除「多 context / 跨运行共享目录或 schema → 残留与锁竞争」一类问题。
 * （§6.4 决策 7 索引可重建纪律同样适用：测试每次从 git 全量重建，数据天然幂等。）
 */
abstract class TestDbIsolation {

    companion object {
        /** 清空管理库业务表（同一测试类的多个用例共享 schema，用例前清空保证断言互不影响） */
        fun clearManagementTables(jdbc: JdbcTemplate) {
            for (table in listOf("comment", "notification", "subscription", "review", "changelog", "mock_rule")) {
                jdbc.update("DELETE FROM $table")
            }
        }
    }
}
