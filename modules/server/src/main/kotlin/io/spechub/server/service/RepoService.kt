package io.spechub.server.service

import io.spechub.server.repository.ProjectEntity
import io.spechub.server.repository.ProjectRepository
import io.spechub.server.repository.RepoEntity
import io.spechub.server.repository.RepoRepository
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SyncStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 项目/仓库域 Service（Phase 2 第二个纵切）：仓库注册与同步状态从投影库迁入管理库。
 *
 * - 投影库不再有 project/repo 表；投影表的 repo_id 只是逻辑列，值取本服务返回的 repo.id。
 * - `register` 幂等（project + url 唯一），`updateSyncStatus` 是摄取回写管理库的唯一入口。
 */
@Service
class RepoService(
    private val projects: ProjectRepository,
    private val repos: RepoRepository,
) {
    fun projectIdByName(name: String): Long? = projects.findByName(name)?.id

    fun listProjects(): List<ProjectRecord> = projects.findAll().sortedBy { it.id }.map { ProjectRecord(checkNotNull(it.id) { "project 必有 id" }, it.name) }

    /** 默认项目等幂等确保（不存在则创建） */
    @Transactional
    fun ensureProject(name: String): Long {
        projects.findByName(name)?.let { return checkNotNull(it.id) { "project 必有 id" } }
        val saved = projects.save(ProjectEntity(name = name, createdAt = Instant.now()))
        return checkNotNull(saved.id) { "project 创建后应回填 id" }
    }

    /** 注册仓库：同（project, url）幂等返回既存行 */
    @Transactional
    fun register(
        projectName: String,
        url: String,
        branch: String = "main",
    ): RepoRecord {
        val projectId = ensureProject(projectName)
        repos.findByProjectIdAndUrl(projectId, url)?.let { return it.toRecord() }
        val saved =
            repos.save(
                RepoEntity(
                    projectId = projectId,
                    url = url,
                    branch = branch,
                    syncStatus = SyncStatus.NEW,
                    createdAt = Instant.now(),
                ),
            )
        return saved.toRecord()
    }

    fun listRepos(): List<RepoRecord> = repos.findAllByOrderByIdAsc().map { it.toRecord() }

    fun getRepo(id: Long): RepoRecord? = repos.findById(id).orElse(null)?.toRecord()

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
}
