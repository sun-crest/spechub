package io.spechub.server.service

import io.spechub.server.repository.ReleaseEntity
import io.spechub.server.repository.ReleaseRepository
import io.spechub.server.repository.RepoRepository
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.ReleaseSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 发布状态 Service（Phase 2）：release 标记是管理数据真源，无法从 git 重建。
 *
 * - `promote`：写管理库 release 记录（repo_id + path → spec_hash），先查后写幂等。
 * - `replay`：投影重建（摄取）后，按管理库记录重放 release 快照；
 *   spec_hash 对应版本不存在（git 已前进且旧内容未重建）时跳过。
 * - 投影层 `version.stage` 只是派生值，重建后由 replay 恢复。
 */
@Service
class ReleaseService(
    private val releases: ReleaseRepository,
    private val repos: RepoRepository,
) {
    @Transactional
    fun promote(
        repoId: Long,
        path: String,
        specHash: String,
    ): ReleaseEntity {
        val existing = releases.findByRepoIdAndPath(repoId, path)
        val orgId = orgIdFor(repoId)
        val saved =
            if (existing != null) {
                releases.save(existing.copy(orgId = orgId, specHash = specHash, releasedAt = Instant.now()))
            } else {
                releases.save(ReleaseEntity(repoId = repoId, orgId = orgId, path = path, specHash = specHash, releasedAt = Instant.now()))
            }
        return saved
    }

    fun find(
        repoId: Long,
        path: String,
    ): ReleaseEntity? = releases.findByRepoIdAndPath(repoId, path)

    fun list(repoId: Long): List<ReleaseEntity> = releases.findAllByRepoId(repoId)

    /**
     * 投影重建后重放 release 快照（幂等；hash 不存在则跳过）。
     * 整批交投影库单事务执行：N 个发布标记只提交一次，避免逐项连接/事务/fsync（编码规范 §5.1）。
     */
    fun replay(
        repoId: Long,
        projection: ProjectionStore,
    ) {
        val specs = releases.findAllByRepoId(repoId).map { ReleaseSpec(it.path, it.specHash) }
        projection.promotePaths(repoId, specs)
    }

    private fun orgIdFor(repoId: Long): Long = checkNotNull(repos.findById(repoId).orElse(null)?.orgId) { "repo $repoId 未归属组织" }
}
