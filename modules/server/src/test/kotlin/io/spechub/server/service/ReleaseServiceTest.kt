package io.spechub.server.service

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Stage
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
import kotlin.test.assertTrue

/** 发布状态迁管理库：promote 记录幂等 + 投影重建后 replay 恢复 release 快照。 */
@SpringBootTest
class ReleaseServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var releaseService: ReleaseService

    @Autowired
    lateinit var projection: ProjectionStore

    @Autowired
    lateinit var db: IndexDb

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-releasetest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_release")
        }
    }

    private fun seedProjection(
        repoId: Long,
        path: String,
        hash: String,
    ) {
        val version = projection.upsertVersion(repoId, path, hash)
        projection.replaceApisForRepo(
            repoId,
            listOf(ApiRecord(repoId = repoId, versionId = version.id, method = "GET", path = "/pets", specJson = """{"hash":"$hash"}""")),
        )
    }

    @Test
    fun `promote 幂等 - 同 path 更新 specHash`() {
        val repo = repoService.register("default", "file:///release-${UUID.randomUUID()}", "main")
        val path = "specs/a.yaml"
        val a = releaseService.promote(repo.id, path, "hash-1")
        val b = releaseService.promote(repo.id, path, "hash-2")
        assertEquals(a.id, b.id, "同 repo+path 更新既有记录")
        assertEquals("hash-2", releaseService.find(repo.id, path)?.specHash)
        assertEquals(1, releaseService.list(repo.id).size)
    }

    @Test
    fun `投影重建后 replay 恢复 release 快照`() {
        val repo = repoService.register("default", "file:///release-replay-${UUID.randomUUID()}", "main")
        val path = "specs/pet/openapi.yaml"
        val hash = "hash-release"

        seedProjection(repo.id, path, hash)
        releaseService.promote(repo.id, path, hash)
        assertNotNull(projection.promotePath(repo.id, path, hash), "首次发布应有投影")
        assertEquals(1, projection.listApis(repo.id, stage = Stage.RELEASE).size)

        // 模拟投影重建：清空 version（级联清 api/schema/doc），再按当前内容重建 draft
        db.execute("DELETE FROM version")
        seedProjection(repo.id, path, hash)
        assertEquals(0, projection.listApis(repo.id, stage = Stage.RELEASE).size, "重建后 release 尚未重放")

        // 摄取完成后的重放（Ingestor 注入的 replayReleases 调用同一逻辑）
        releaseService.replay(repo.id, projection)
        assertEquals(1, projection.listApis(repo.id, stage = Stage.RELEASE).size, "release 快照应由管理库记录重放恢复")
        assertEquals(1, projection.listApis(repo.id, stage = Stage.DRAFT).size, "draft 不受影响")
    }

    @Test
    fun `replay 批量恢复多个路径 - 缺失 hash 单独跳过`() {
        val repo = repoService.register("default", "file:///release-batch-${UUID.randomUUID()}", "main")
        val okPath = "specs/batch-ok.yaml"
        val missingPath = "specs/batch-missing.yaml"
        val hash = "hash-batch-ok"

        seedProjection(repo.id, okPath, hash)
        releaseService.promote(repo.id, okPath, hash)
        releaseService.promote(repo.id, missingPath, "hash-absent-in-projection")
        assertNotNull(projection.promotePath(repo.id, okPath, hash), "首次发布应有投影")

        // 模拟投影重建：清空 version（级联清 api/schema/doc），再按当前内容重建 draft
        db.execute("DELETE FROM version")
        seedProjection(repo.id, okPath, hash)
        assertEquals(0, projection.listApis(repo.id, stage = Stage.RELEASE).size, "重建后 release 尚未重放")

        // 单事务批量重放：存在的 hash 恢复，缺失的跳过，不因单项缺失中断整批
        releaseService.replay(repo.id, projection)
        assertEquals(1, projection.listApis(repo.id, stage = Stage.RELEASE).size, "存在的 hash 应恢复")
        assertEquals(1, projection.listApis(repo.id, stage = Stage.DRAFT).size, "draft 不受影响")
    }

    @Test
    fun `replay 跳过不存在的 specHash - 不抛错`() {
        val repo = repoService.register("default", "file:///release-missing-${UUID.randomUUID()}", "main")
        val path = "specs/missing.yaml"
        releaseService.promote(repo.id, path, "hash-not-in-projection")
        seedProjection(repo.id, path, "hash-current")
        releaseService.replay(repo.id, projection)
        assertTrue(projection.listApis(repo.id, stage = Stage.RELEASE).isEmpty(), "旧 hash 不存在时跳过重放")
    }
}
