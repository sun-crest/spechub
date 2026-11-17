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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 阶段 A：项目空间 CRUD / slug 唯一 / 归档后删除。 */
@SpringBootTest
class ProjectServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var repos: RepoService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-projecttest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_project_svc")
        }
    }

    private fun uniqueSlug(): String =
        "p-" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    @Test
    fun `创建项目 - 默认 private 且 slug 唯一`() {
        val orgId = orgs.defaultOrgId()
        val slug = uniqueSlug()
        val project = projects.create(orgId, "项目 $slug", slug)
        assertNotNull(project.id)
        assertEquals("private", project.visibility)
        assertEquals(orgId, project.orgId)
        assertEquals(slug, project.slug)

        assertFailsWith<IllegalArgumentException> {
            projects.create(orgId, "重复 $slug", slug)
        }
    }

    @Test
    fun `删除前必须归档且无仓库`() {
        val project = projects.create(orgs.defaultOrgId(), "删除测试", uniqueSlug())
        val id = assertNotNull(project.id)

        assertFailsWith<IllegalStateException> { projects.delete(id) }

        projects.archive(id)
        projects.delete(id)
        assertNull(projects.get(id))
    }

    @Test
    fun `有仓库时不能删除`() {
        val project = projects.create(orgs.defaultOrgId(), "有仓库", uniqueSlug())
        val id = assertNotNull(project.id)
        repos.register(id, "file:///project-delete-${UUID.randomUUID()}", "main")
        projects.archive(id)

        assertFailsWith<IllegalStateException> { projects.delete(id) }
        assertNotNull(projects.get(id))
    }
}
