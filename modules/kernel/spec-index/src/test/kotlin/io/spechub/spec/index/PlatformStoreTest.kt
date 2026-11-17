package io.spechub.spec.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 管理域：project / repo / changelog / mock_rule / user / comment / subscription / review。 */
class PlatformStoreTest {

    @TempDir
    lateinit var temp: Path

    private fun stores(): Pair<PlatformStore, ProjectionStore> {
        val db = IndexDb(temp.resolve("platform-test.db"))
        db.migrate()
        return PlatformStore(db) to ProjectionStore(db)
    }

    @Test
    fun `repo 注册幂等 - 同 URL 不重复建行`() {
        val (platform, _) = stores()
        val a = platform.insertRepo("default", "https://example.com/org/repo.git")
        val b = platform.insertRepo("default", "https://example.com/org/repo.git")
        assertEquals(a.id, b.id, "同 URL 幂等")
        assertEquals(1, platform.listRepos().size)
    }

    // ── changelog 与 mock_rule ────────────────────────────────────────────────

    @Test
    fun `changelog 追加与按 repo 逆序查询`() {
        val (platform, _) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git")
        platform.addChangelog(repo.id, null, "spechub-bot", ChangelogAction.SPEC_WRITE, "write a")
        platform.addChangelog(repo.id, null, "spechub-bot", ChangelogAction.PROMOTE, "release b")
        val logs = platform.listChangelogs(repo.id, null, limit = 10)
        assertEquals(2, logs.size)
        assertEquals(ChangelogAction.PROMOTE, logs.first().action, "逆序：最新在前")
        assertEquals("spechub-bot", logs.first().actor)
    }

    @Test
    fun `mock rule 幂等 upsert 与删除`() {
        val (platform, _) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git", "main")
        val r1 = platform.upsertMockRule(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "A"))
        val r2 = platform.upsertMockRule(MockRuleRecord(repoId = repo.id, apiId = null, fieldPattern = "user.name", ruleType = "value", ruleValue = "B"))
        assertEquals(r1.id, r2.id, "同（api,pattern,type）幂等 upsert")
        assertEquals("B", platform.listMockRules(repo.id).single().ruleValue, "更新值")
        platform.deleteMockRule(checkNotNull(r1.id) { "mock rule upsert 应回填 id" })
        assertEquals(0, platform.listMockRules(repo.id).size)
    }

    // ── comment ──────────────────────────────────────────────────────────────

    @Test
    fun `comment 增查删 - 按 repo+method+path 稳定寻址`() {
        val (platform, projection) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git")
        projection.replaceApisForRepo(repo.id, listOf(
            ApiRecord(repoId = repo.id, method = "GET", path = "/pets", specJson = "{}"),
        ))
        val apiId = projection.listApis(repo.id).single().id!!

        val c1 = platform.addComment(repo.id, apiId, null, "GET", "/pets", "alice", "这个路径参数缺 required")
        platform.addComment(repo.id, apiId, null, "GET", "/pets", "bob", "收到，下版补上")
        val list = platform.listCommentsForEndpoint(repo.id, "GET", "/pets")
        assertEquals(2, list.size)
        assertEquals("alice", list.first().author)
        assertEquals("收到，下版补上", list.last().body, "时间正序")
        assertEquals("GET", list.first().method)
        assertEquals("/pets", list.first().path)

        assertNotNull(platform.getComment(c1.id!!))
        platform.deleteComment(c1.id!!)
        assertEquals(1, platform.listCommentsForEndpoint(repo.id, "GET", "/pets").size)

        // 投影重建（摄取 DELETE+INSERT）后评论仍在：api_id 非 FK，稳定寻址命中
        projection.replaceApisForRepo(repo.id, listOf(
            ApiRecord(repoId = repo.id, method = "GET", path = "/pets", specJson = "{}"),
        ))
        assertEquals(1, platform.listCommentsForEndpoint(repo.id, "GET", "/pets").size, "投影重建不丢评论")
    }

    @Test
    fun `doc 评论按 repo+path 稳定寻址 - 投影重建后仍命中`() {
        val (platform, projection) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git")
        val docPath = "docs/pet/handover.md"
        projection.replaceDocsForRepo(repo.id, listOf(
            DocRecord(repoId = repo.id, path = docPath, title = "接手分析", markdown = "第一版"),
        ))
        val doc = projection.listDocs(repo.id).single()
        platform.addComment(repo.id, null, doc.id, "", docPath, "alice", "文档评论")

        // doc 投影重建（DELETE + INSERT）：doc.id 变化，评论仍按 repo + path 命中
        projection.replaceDocsForRepo(repo.id, listOf(
            DocRecord(repoId = repo.id, path = docPath, title = "接手分析", markdown = "第二版"),
        ))
        val rebuilt = projection.listDocs(repo.id).single()
        assertTrue(rebuilt.id != doc.id, "重建后 doc id 应变化")
        val comments = platform.listCommentsForDoc(repo.id, docPath)
        assertEquals(1, comments.size, "重建后文档评论仍命中")
        assertEquals("alice", comments.single().author)
    }

    // ── subscription / notification ──────────────────────────────────────────

    @Test
    fun `subscription 幂等 upsert 与通知记录`() {
        val (platform, _) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git")

        val s1 = platform.upsertSubscription(repo.id, null, "GET", "/pets", "pay-team")
        val s2 = platform.upsertSubscription(repo.id, null, "GET", "/pets", "pay-team")
        assertEquals(s1.id, s2.id, "同（repo,method,path,team）幂等 upsert")
        assertEquals(1, platform.listSubscriptions(repo.id).size)

        platform.addNotification("pay-team", repo.id, "GET", "/pets", "结构变更 a")
        platform.addNotification("pay-team", repo.id, "GET", "/pets", "结构变更 b")
        assertEquals(2, platform.listNotifications("pay-team").size)
        assertEquals(1, platform.listSubscriptionsForEndpoint(repo.id, "GET", "/pets").size)

        assertEquals("GET", platform.listSubscriptionsForEndpoint(repo.id, "GET", "/pets").single().method)
        platform.deleteSubscription(s1.id!!)
        assertEquals(0, platform.listSubscriptions(repo.id).size, "取消订阅后查询为空")
        assertEquals(2, platform.listNotifications("pay-team").size, "通知历史不随订阅删除而清空（供审计）")
    }

    // ── review ───────────────────────────────────────────────────────────────

    @Test
    fun `review 创建查询与审批状态流转`() {
        val (platform, _) = stores()
        val repo = platform.insertRepo("default", "https://example.com/o/r.git")

        val r1 = platform.createReview(repo.id, "specs/pet/openapi.yaml", "new content v2", "diff...", "hash123", "alice")
        val r2 = platform.createReview(repo.id, "specs/pet/openapi.yaml", "new content v3", "diff...", "hash456", "bob")
        assertEquals(ReviewStatus.PENDING, r1.status, "新评审默认 pending")
        assertEquals(2, platform.listReviews(repo.id).size)
        assertEquals(2, platform.listReviews(status = ReviewStatus.PENDING).size)

        platform.resolveReview(r1.id!!, ReviewStatus.APPROVED, "reviewer")
        val approved = platform.getReview(r1.id)!!
        assertEquals(ReviewStatus.APPROVED, approved.status)
        assertEquals("reviewer", approved.reviewer, "审批人记录")
        assertNotNull(approved.resolvedAt, "审批时间戳")
        assertEquals(1, platform.listReviews(status = ReviewStatus.APPROVED).size)

        platform.resolveReview(r2.id!!, ReviewStatus.REJECTED, "reviewer", "缺 required 字段")
        assertEquals("缺 required 字段", platform.getReview(r2.id)!!.rejectReason, "打回理由")
        assertEquals(0, platform.listReviews(repoId = repo.id, status = ReviewStatus.PENDING).size, "两条均处理后无待审")
    }
}
