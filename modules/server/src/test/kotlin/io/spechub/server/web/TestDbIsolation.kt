package io.spechub.server.web

/**
 * 测试投影目录隔离标记基类：每个 @SpringBootTest 测试类在自身 companion 里
 * 用 @TempDir + @DynamicPropertySource 声明全新临时 gitsource/sqlite/artifacts，
 * 彻底消除「多 context / 跨运行共享 build/tmp 固定目录 → 偶发 SQLITE_BUSY 与残留」一类问题。
 * （§6.4 决策 7 索引可重建纪律同样适用：测试每次从 git 全量重建，数据天然幂等。）
 */
abstract class TestDbIsolation