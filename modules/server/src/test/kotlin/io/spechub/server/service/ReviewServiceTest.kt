package io.spechub.server.service

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import io.spechub.spec.index.ReviewStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 评审域迁入管理库后的集成验证（创建 / 组合查询 / 审批状态流转）。 */
@SpringBootTest
class ReviewServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var reviewService: ReviewService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-reviewtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_review_svc")
        }
    }

    @Test
    fun `评审创建查询与审批状态流转`() {
        val repo = repoService.register("default", "file:///review-${UUID.randomUUID()}", "main")

        val r1 = reviewService.create(repo.id, "specs/pet/openapi.yaml", "new content v2", "diff...", "hash123", "alice")
        val r2 = reviewService.create(repo.id, "specs/pet/openapi.yaml", "new content v3", "diff...", "hash456", "bob")
        assertEquals(ReviewStatus.PENDING, r1.status, "新评审默认 pending")
        assertEquals(2, reviewService.list(repo.id).size)
        assertEquals(2, reviewService.list(status = ReviewStatus.PENDING).size)

        assertTrue(reviewService.resolve(checkNotNull(r1.id) { "评审应有 id" }, ReviewStatus.APPROVED, "reviewer"), "首次审批成功")
        val approved = assertNotNull(reviewService.get(checkNotNull(r1.id)))
        assertEquals(ReviewStatus.APPROVED, approved.status)
        assertEquals("reviewer", approved.reviewer)
        assertNotNull(approved.resolvedAt, "审批时间戳")
        assertEquals(1, reviewService.list(status = ReviewStatus.APPROVED).size)
        // 状态机：已处理的评审不可再次迁移（并发/重复审批兜底）
        assertFalse(reviewService.resolve(checkNotNull(r1.id), ReviewStatus.REJECTED, "another"), "已处理评审应拒绝再次迁移")

        assertTrue(reviewService.resolve(checkNotNull(r2.id), ReviewStatus.REJECTED, "reviewer", "缺 required 字段"))
        assertEquals("缺 required 字段", reviewService.get(checkNotNull(r2.id))?.rejectReason, "打回理由")
        assertEquals(0, reviewService.list(repo.id, ReviewStatus.PENDING).size, "两条均处理后无待审")
    }
}
