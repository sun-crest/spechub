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

/** 项目/仓库域迁入管理库后的集成验证（Flyway + Spring Data JDBC + @Transactional）。 */
@SpringBootTest
class RepoServiceTest : TestDbIsolation() {

    @Autowired
    lateinit var repoService: RepoService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-repotest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_reposervice")
        }
    }

    @Test
    fun `注册幂等 - 同 project+url 不重复建行并自动建项目`() {
        val url = "file:///repo-${UUID.randomUUID()}"
        val a = repoService.register("default", url, "main")
        val b = repoService.register("default", url, "main")
        assertEquals(a.id, b.id, "同 URL 幂等")
        assertNotNull(repoService.projectIdByName("default"), "项目应自动创建")
        assertEquals(1, repoService.listRepos().count { it.url == url }, "同 URL 只有一行")
    }

    @Test
    fun `同步状态回写管理库`() {
        val repo = repoService.register("default", "file:///sync-${UUID.randomUUID()}", "main")
        repoService.updateSyncStatus(repo.id, "ok", null)
        assertEquals("ok", repoService.getRepo(repo.id)?.syncStatus)
        repoService.updateSyncStatus(repo.id, "error", "boom")
        assertEquals("boom", repoService.getRepo(repo.id)?.lastError)
    }
}
