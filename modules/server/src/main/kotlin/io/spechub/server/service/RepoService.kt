package io.spechub.server.service

import io.spechub.server.repository.RepoBindingEntity
import io.spechub.server.repository.RepoEntity
import io.spechub.server.repository.RepoRepository
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SyncStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

/**
 * 项目/仓库域 Service（阶段 A：项目逻辑迁到 [ProjectService]，仓库注册必须归属项目）。
 *
 * - 注册仓库时同时创建首条 active binding（managed），保证「每个 repo 恰好一条 active」。
 * - 旧的 `register(projectName, ...)` / `ensureProject(name)` 保留给现有调用方与测试，
 *   A5/A7 的 Controller 再切到 projectId / binding API。
 */
@Service
class RepoService(
    private val repos: RepoRepository,
    private val projectService: ProjectService,
    private val bindings: RepoBindingService,
) {
    fun projectIdByName(name: String): Long? = projectService.projectIdByName(name)

    fun listProjects(): List<ProjectRecord> = projectService.listProjects()

    /** 默认项目等幂等确保（委托 ProjectService）。 */
    @Transactional
    fun ensureProject(name: String): Long = projectService.ensureProject(name)

    /** 旧调用方兼容：按项目名注册（默认组织）。 */
    @Transactional
    fun register(
        projectName: String,
        url: String,
        branch: String = "main",
    ): RepoRecord = register(projectService.ensureProject(projectName), url, branch)

    /**
     * 新写路径：必须显式归属项目；注册时创建逻辑仓库 + 首个 `active` binding。
     * 同（project, url）幂等返回既存行，并补齐 active binding。
     */
    @Transactional
    fun register(
        projectId: Long,
        url: String,
        branch: String = "main",
        kind: String = RepoBindingEntity.KIND_MANAGED,
        connectionId: Long? = null,
        externalRepoId: String? = null,
    ): RepoRecord {
        val project = projectService.get(projectId) ?: throw IllegalArgumentException("project $projectId 不存在")
        val orgId = project.orgId ?: throw IllegalStateException("项目 $projectId 未归属组织")
        repos.findByProjectIdAndUrl(projectId, url)?.let { existing ->
            bindings.ensureActiveManaged(existing)
            return existing.toRecord()
        }
        val resolvedExternalId = externalRepoId?.takeIf { it.isNotBlank() } ?: md5(url)
        val saved =
            repos.save(
                RepoEntity(
                    projectId = projectId,
                    orgId = orgId,
                    url = url,
                    branch = branch,
                    syncStatus = SyncStatus.NEW,
                    connectionId = connectionId,
                    externalRepoId = resolvedExternalId,
                    createdAt = Instant.now(),
                ),
            )
        bindings.createActive(saved, kind, connectionId, resolvedExternalId)
        return saved.toRecord()
    }

    /** 摄取 / 写回使用的真源（active binding）；没有 active 时返回 null。 */
    fun sourceOf(repoId: Long): RepoBindingService.Source? = bindings.activeSource(repoId)

    fun listRepos(): List<RepoRecord> = repos.findAllByOrderByIdAsc().map { it.toRecord() }

    fun listReposByProjectIds(projectIds: Collection<Long>): List<RepoRecord> =
        if (projectIds.isEmpty()) {
            emptyList()
        } else {
            repos.findAllByProjectIdIn(projectIds).map { it.toRecord() }
        }

    fun listReposByIds(ids: Collection<Long>): List<RepoRecord> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            repos.findAllById(ids).map { it.toRecord() }
        }

    fun listReposByOrgId(orgId: Long): List<RepoRecord> = repos.findAllByOrgIdOrderByIdAsc(orgId).map { it.toRecord() }

    fun getRepo(id: Long): RepoRecord? = repos.findById(id).orElse(null)?.toRecord()

    fun getRepoEntity(id: Long): RepoEntity? = repos.findById(id).orElse(null)

    fun orgIdOf(repoId: Long): Long? = getRepoEntity(repoId)?.orgId

    /** 摄取期同步状态回写（管理库唯一写入口） */
    fun updateSyncStatus(
        repoId: Long,
        status: String,
        error: String? = null,
    ) {
        val repo = repos.findById(repoId).orElse(null) ?: return
        if (repo.syncStatus == status && repo.lastError == error) return
        repos.save(repo.copy(syncStatus = status, lastError = error))
    }

    private fun RepoEntity.toRecord(): RepoRecord =
        RepoRecord(
            id = checkNotNull(id) { "repo 必有 id" },
            projectId = projectId,
            url = url,
            branch = branch,
            syncStatus = syncStatus,
            lastError = lastError,
        )

    private fun md5(text: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
