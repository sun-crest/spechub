package io.spechub.server.service

import io.spechub.server.repository.ReleaseEntity
import io.spechub.server.repository.ReleaseRepository
import io.spechub.spec.index.ProjectionStore
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
) {

    @Transactional
    fun promote(repoId: Long, path: String, specHash: String): ReleaseEntity {
        val existing = releases.findByRepoIdAndPath(repoId, path)
        val saved = if (existing != null) {
            releases.save(existing.copy(specHash = specHash, releasedAt = Instant.now()))
        } else {
            releases.save(ReleaseEntity(repoId = repoId, path = path, specHash = specHash, releasedAt = Instant.now()))
        }
        return saved
    }

    fun find(repoId: Long, path: String): ReleaseEntity? = releases.findByRepoIdAndPath(repoId, path)

    fun list(repoId: Long): List<ReleaseEntity> = releases.findAllByRepoId(repoId)

    /** 投影重建后重放 release 快照（幂等；hash 不存在则跳过） */
    fun replay(repoId: Long, projection: ProjectionStore) {
        for (release in releases.findAllByRepoId(repoId)) {
            projection.promotePath(repoId, release.path, release.specHash)
        }
    }
}
