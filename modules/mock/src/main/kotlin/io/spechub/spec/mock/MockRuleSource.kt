package io.spechub.spec.mock

import io.spechub.spec.index.MockRuleRecord

/**
 * Mock 规则来源端口（kernel/mock 侧窄接口）。
 *
 * mock 规则是管理数据（Phase 2 起在管理库）；mock 模块不依赖 server，
 * 由 server 的 MockRuleService 实现本接口并注入 MockEngine。
 */
interface MockRuleSource {
    fun rulesFor(repoId: Long): List<MockRuleRecord>
}
