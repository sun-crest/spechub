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

/** 变更记录域（Phase 2 最后一个迁入管理库的域）：追加 + 组合查询逆序。 */
@SpringBootTest
class ChangelogServiceTest : TestDbIsolation() {

    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var changelogService: ChangelogService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-changelogtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_changelog_svc")
        }
    }

    @Test
    fun `追加与按 repo-api 组合查询 - 最新在前`() {
        val repo = repoService.register("default", "file:///changelog-${UUID.randomUUID()}", "main")

        changelogService.add(repo.id, null, "spechub-bot", "spec.write", "write a")
        changelogService.add(repo.id, 42L, "spechub-bot", "promote", "release b")
        changelogService.add(null, null, "alice", "user.create", "创建用户 carol")

        assertEquals(3, changelogService.list(limit = 10).size)
        assertEquals(2, changelogService.list(repoId = repo.id, limit = 10).size)
        assertEquals(1, changelogService.list(apiId = 42L, limit = 10).size)
        assertEquals(1, changelogService.list(repoId = repo.id, apiId = 42L, limit = 10).size)
        assertEquals("promote", changelogService.list(repoId = repo.id, limit = 10).first().action, "逆序：最新在前")
    }
}
