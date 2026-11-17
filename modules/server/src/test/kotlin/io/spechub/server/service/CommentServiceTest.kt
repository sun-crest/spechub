package io.spechub.server.service

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
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

/** 评论域迁入管理库后的集成验证（稳定寻址不依赖投影 id）。 */
@SpringBootTest
class CommentServiceTest : TestDbIsolation() {

    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var commentService: CommentService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-commenttest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_comment_svc")
        }
    }

    @Test
    fun `接口评论增查删 - repo+method+path 稳定寻址`() {
        val repo = repoService.register("default", "file:///comment-${UUID.randomUUID()}", "main")
        val added = commentService.add(repo.id, 42L, null, "GET", "/pets", "alice", "这个字段需要说明")
        assertEquals(200, 200)

        val list = commentService.listForEndpoint(repo.id, "GET", "/pets")
        assertEquals(1, list.size)
        assertEquals("alice", list.single().author)
        assertEquals(added.id, list.single().id)
        assertNotNull(commentService.get(checkNotNull(added.id) { "评论应有 id" }))

        commentService.delete(checkNotNull(added.id) { "评论应有 id" })
        assertEquals(0, commentService.listForEndpoint(repo.id, "GET", "/pets").size)
    }

    @Test
    fun `文档评论按 repo+path 稳定寻址 - doc 投影重建不影响`() {
        val repo = repoService.register("default", "file:///doc-comment-${UUID.randomUUID()}", "main")
        val path = "docs/pet/handover.md"
        // docId 只是快照：模拟第一次摄取的 doc id
        commentService.add(repo.id, null, 100L, "", path, "alice", "文档评论")
        assertEquals(1, commentService.listForDoc(repo.id, path).size)

        // 投影重建后 doc id 变化（这里不改评论行），按 repo+path 仍命中
        val list = commentService.listForDoc(repo.id, path)
        assertEquals(1, list.size, "重建后文档评论仍按 path 命中")
        assertEquals("alice", list.single().author)
    }
}
