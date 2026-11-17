package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 尚未迁管理库的过渡管理域：changelog / mock_rule。 */
class PlatformStoreTest {

    @TempDir
    lateinit var temp: Path

    private fun stores(): Pair<PlatformStore, ProjectionStore> {
        val db = IndexDb(temp.resolve("platform-test.db"))
        db.migrate()
        return PlatformStore(db) to ProjectionStore(db)
    }

    // ── changelog 与 mock_rule ────────────────────────────────────────────────

    @Test
    fun `changelog 追加与按 repo 逆序查询`() {
        val (platform, _) = stores()
        val repoId = 1L
        platform.addChangelog(repoId, null, "spechub-bot", ChangelogAction.SPEC_WRITE, "write a")
        platform.addChangelog(repoId, null, "spechub-bot", ChangelogAction.PROMOTE, "release b")
        val logs = platform.listChangelogs(repoId, null, limit = 10)
        assertEquals(2, logs.size)
        assertEquals(ChangelogAction.PROMOTE, logs.first().action, "逆序：最新在前")
        assertEquals("spechub-bot", logs.first().actor)
    }

    @Test
    fun `mock rule 幂等 upsert 与删除`() {
        val (platform, _) = stores()
        val repoId = 1L
        val r1 = platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "A"))
        val r2 = platform.upsertMockRule(MockRuleRecord(repoId = repoId, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "B"))
        assertEquals(r1.id, r2.id, "同（api,pattern,type）幂等 upsert")
        assertEquals("B", platform.listMockRules(repoId).single().ruleValue, "更新值")
        platform.deleteMockRule(checkNotNull(r1.id) { "mock rule upsert 应回填 id" })
        assertEquals(0, platform.listMockRules(repoId).size)
    }
}
