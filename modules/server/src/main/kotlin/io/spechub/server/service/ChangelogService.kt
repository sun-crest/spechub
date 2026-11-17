package io.spechub.server.service

import io.spechub.server.repository.ChangelogEntity
import io.spechub.server.repository.ChangelogRepository
import io.spechub.server.repository.RepoRepository
import io.spechub.spec.index.ChangelogRecord
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 变更记录 Service（Phase 2 最后一个迁入管理库的域）：写操作审计留痕。
 *
 * 与业务写不在同一事务（跨域/跨表编排由调用方按固定次序执行）；changelog 只追加、不更新。
 */
@Service
class ChangelogService(
    private val changelogs: ChangelogRepository,
    private val repos: RepoRepository,
    private val orgs: OrgService,
) {
    fun add(
        repoId: Long?,
        apiId: Long?,
        actor: String,
        action: String,
        detail: String,
        orgId: Long? = null,
    ): ChangelogRecord {
        val resolvedOrgId = orgId ?: repoId?.let { repos.findById(it).orElse(null)?.orgId } ?: orgs.defaultOrgId()
        val saved =
            changelogs.save(
                ChangelogEntity(
                    repoId = repoId,
                    orgId = resolvedOrgId,
                    apiId = apiId,
                    actor = actor,
                    action = action,
                    detail = detail,
                    createdAt = Instant.now(),
                ),
            )
        return saved.toRecord()
    }

    fun list(
        repoId: Long? = null,
        apiId: Long? = null,
        limit: Int = 50,
    ): List<ChangelogRecord> {
        val entities =
            when {
                repoId != null && apiId != null -> changelogs.findByRepoIdAndApiId(repoId, apiId, limit)
                repoId != null -> changelogs.findByRepoId(repoId, limit)
                apiId != null -> changelogs.findByApiId(apiId, limit)
                else -> changelogs.findLatest(limit)
            }
        return entities.map { it.toRecord() }
    }

    /** 组织级变更流（阶段 A 用于 A4 读隔离；repoId 为空的组织动作也在这里）。 */
    fun listByOrg(
        orgId: Long,
        limit: Int = 50,
    ): List<ChangelogRecord> = changelogs.findLatestByOrg(orgId, limit).map { it.toRecord() }

    private fun ChangelogEntity.toRecord(): ChangelogRecord =
        ChangelogRecord(
            id = id,
            repoId = repoId,
            apiId = apiId,
            actor = actor,
            action = action,
            detail = detail,
            createdAt = createdAt.toString(),
        )
}
