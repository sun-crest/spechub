package io.spechub.server.service

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import io.spechub.spec.index.MockRuleRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Mock 规则域迁入管理库后的集成验证（幂等 upsert / MockRuleSource 端口 / 删除）。 */
@SpringBootTest
class MockRuleServiceTest : TestDbIsolation() {

    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var mockRuleService: MockRuleService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-mockruletest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_mockrule_svc")
        }
    }

    @Test
    fun `规则幂等 upsert - 项目级与接口级 - 并可经端口读取`() {
        val repo = repoService.register("default", "file:///mockrule-${UUID.randomUUID()}", "main")

        val projectRule = mockRuleService.upsert(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "A"))
        val updated = mockRuleService.upsert(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "B"))
        assertEquals(projectRule.id, updated.id, "同（api,pattern,type）幂等 upsert")
        assertEquals("B", mockRuleService.rulesFor(repo.id).single().ruleValue, "更新值")

        val apiRule = mockRuleService.upsert(MockRuleRecord(repoId = repo.id, apiId = 42L, fieldPattern = "user.name", ruleType = "value", ruleValue = "C"))
        assertNotNull(apiRule.id)
        assertEquals(2, mockRuleService.rulesFor(repo.id).size, "项目级与接口级规则并存")

        mockRuleService.delete(checkNotNull(projectRule.id) { "规则应有 id" })
        assertEquals(1, mockRuleService.rulesFor(repo.id).size)
    }
}
