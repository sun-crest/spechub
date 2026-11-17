package io.spechub.server.service

import io.spechub.server.repository.RepoBindingEntity
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 阶段 A：repo_binding 单主约束、verify 元数据短路、activate/retire/delete 状态机。 */
@SpringBootTest
class RepoBindingServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var bindings: RepoBindingService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-bindingtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_binding_svc")
        }
    }

    private fun newRepo(): Long {
        val project =
            projects.create(
                orgs.defaultOrgId(),
                "binding ${UUID.randomUUID()}",
                "b-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(12),
            )
        return repoService.register(assertNotNull(project.id), "file:///binding-${UUID.randomUUID()}", "main").id
    }

    @Test
    fun `注册仓库自动创建唯一 active binding`() {
        val repoId = newRepo()
        val all = bindings.list(repoId)
        assertEquals(1, all.count { it.status == RepoBindingEntity.STATUS_ACTIVE })
        assertEquals(1, all.size)
        assertNotNull(bindings.active(repoId))
    }

    @Test
    fun `verify 元数据一致直接判 identical`() {
        val repoId = newRepo()
        val active = assertNotNull(bindings.active(repoId))
        val candidate =
            bindings.createCandidate(
                repoId = repoId,
                kind = RepoBindingEntity.KIND_MANAGED,
                remoteUrl = active.remoteUrl,
                branch = active.branch,
                externalRepoId = active.externalRepoId,
            )
        val result = bindings.verify(repoId, assertNotNull(candidate.id))
        assertEquals(RepoBindingService.VERIFY_IDENTICAL, result.status)
    }

    @Test
    fun `activate 翻主 - 旧 active 变 retired 且新仓库仍单 active`() {
        val repoId = newRepo()
        val old = assertNotNull(bindings.active(repoId))
        val candidate =
            bindings.createCandidate(
                repoId = repoId,
                kind = RepoBindingEntity.KIND_MANAGED,
                remoteUrl = "file:///binding-next-${UUID.randomUUID()}",
                branch = "main",
            )

        val activated = bindings.activate(repoId, assertNotNull(candidate.id))
        assertEquals(RepoBindingEntity.STATUS_ACTIVE, activated.status)
        assertEquals(1, bindings.list(repoId).count { it.status == RepoBindingEntity.STATUS_ACTIVE })
        assertEquals(RepoBindingEntity.STATUS_RETIRED, bindings.get(repoId, assertNotNull(old.id)).status)

        assertFailsWith<IllegalArgumentException> { bindings.retire(repoId, assertNotNull(activated.id)) }
        bindings.deleteRetired(repoId, assertNotNull(old.id))
        assertNull(bindings.list(repoId).firstOrNull { it.id == old.id })
    }

    @Test
    fun `candidate 不能直接 retire 后成为 active`() {
        val repoId = newRepo()
        val candidate =
            bindings.createCandidate(
                repoId = repoId,
                kind = RepoBindingEntity.KIND_MANAGED,
                remoteUrl = "file:///candidate-${UUID.randomUUID()}",
                branch = "main",
            )
        val retired = bindings.retire(repoId, assertNotNull(candidate.id))
        assertEquals(RepoBindingEntity.STATUS_RETIRED, retired.status)
        assertTrue(bindings.list(repoId).any { it.status == RepoBindingEntity.STATUS_ACTIVE })
    }
}
