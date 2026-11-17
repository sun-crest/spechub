package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 尚未迁管理库的过渡管理域：changelog / mock_rule / review。 */
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

    // ── review ───────────────────────────────────────────────────────────────

    @Test
    fun `review 创建查询与审批状态流转`() {
        val (platform, _) = stores()
        val repoId = 1L

        val r1 = platform.createReview(repoId, "specs/pet/openapi.yaml", "new content v2", "diff...", "hash123", "alice")
        val r2 = platform.createReview(repoId, "specs/pet/openapi.yaml", "new content v3", "diff...", "hash456", "bob")
        assertEquals(ReviewStatus.PENDING, r1.status, "新评审默认 pending")
        assertEquals(2, platform.listReviews(repoId).size)
        assertEquals(2, platform.listReviews(status = ReviewStatus.PENDING).size)

        platform.resolveReview(r1.id!!, ReviewStatus.APPROVED, "reviewer")
        val approved = platform.getReview(r1.id)!!
        assertEquals(ReviewStatus.APPROVED, approved.status)
        assertEquals("reviewer", approved.reviewer, "审批人记录")
        assertNotNull(approved.resolvedAt, "审批时间戳")
        assertEquals(1, platform.listReviews(status = ReviewStatus.APPROVED).size)

        platform.resolveReview(r2.id!!, ReviewStatus.REJECTED, "reviewer", "缺 required 字段")
        assertEquals("缺 required 字段", platform.getReview(r2.id)!!.rejectReason, "打回理由")
        assertEquals(0, platform.listReviews(repoId = repoId, status = ReviewStatus.PENDING).size, "两条均处理后无待审")
    }
}
