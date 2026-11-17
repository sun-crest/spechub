package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 尚未迁管理库的过渡管理域：comment / changelog / mock_rule / review。 */
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

    // ── comment ──────────────────────────────────────────────────────────────

    @Test
    fun `comment 增查删 - 按 repo+method+path 稳定寻址`() {
        val (platform, projection) = stores()
        val repoId = 1L
        projection.replaceApisForRepo(repoId, listOf(
            ApiRecord(repoId = repoId, method = "GET", path = "/pets", specJson = "{}"),
        ))
        val apiId = projection.listApis(repoId).single().id!!

        val c1 = platform.addComment(repoId, apiId, null, "GET", "/pets", "alice", "这个路径参数缺 required")
        platform.addComment(repoId, apiId, null, "GET", "/pets", "bob", "收到，下版补上")
        val list = platform.listCommentsForEndpoint(repoId, "GET", "/pets")
        assertEquals(2, list.size)
        assertEquals("alice", list.first().author)
        assertEquals("收到，下版补上", list.last().body, "时间正序")
        assertEquals("GET", list.first().method)
        assertEquals("/pets", list.first().path)

        assertNotNull(platform.getComment(c1.id!!))
        platform.deleteComment(c1.id!!)
        assertEquals(1, platform.listCommentsForEndpoint(repoId, "GET", "/pets").size)

        // 投影重建（摄取 DELETE+INSERT）后评论仍在：api_id 非 FK，稳定寻址命中
        projection.replaceApisForRepo(repoId, listOf(
            ApiRecord(repoId = repoId, method = "GET", path = "/pets", specJson = "{}"),
        ))
        assertEquals(1, platform.listCommentsForEndpoint(repoId, "GET", "/pets").size, "投影重建不丢评论")
    }

    @Test
    fun `doc 评论按 repo+path 稳定寻址 - 投影重建后仍命中`() {
        val (platform, projection) = stores()
        val repoId = 1L
        val docPath = "docs/pet/handover.md"
        projection.replaceDocsForRepo(repoId, listOf(
            DocRecord(repoId = repoId, path = docPath, title = "接手分析", markdown = "第一版"),
        ))
        val doc = projection.listDocs(repoId).single()
        platform.addComment(repoId, null, doc.id, "", docPath, "alice", "文档评论")

        // doc 投影重建（DELETE + INSERT）：doc.id 变化，评论仍按 repo + path 命中
        projection.replaceDocsForRepo(repoId, listOf(
            DocRecord(repoId = repoId, path = docPath, title = "接手分析", markdown = "第二版"),
        ))
        val rebuilt = projection.listDocs(repoId).single()
        assertTrue(rebuilt.id != doc.id, "重建后 doc id 应变化")
        val comments = platform.listCommentsForDoc(repoId, docPath)
        assertEquals(1, comments.size, "重建后文档评论仍命中")
        assertEquals("alice", comments.single().author)
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
