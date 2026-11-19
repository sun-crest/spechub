package io.spechub.server.service

import io.spechub.server.repository.MembershipEntity
import io.spechub.server.repository.MembershipRepository
import io.spechub.server.repository.ProjectEntity
import io.spechub.server.repository.ProjectRepository
import io.spechub.server.repository.RepoRepository
import io.spechub.server.repository.ServiceAccountRepository
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 项目空间 Service（阶段 A）：CRUD、归档、可见性、owner 转移、slug 唯一。
 *
 * - `ensureProject(name)` 保留给旧调用方（默认组织 + slug 推导）。
 * - 删除前必须先归档且没有仓库；FK RESTRICT 由 Service 显式编排。
 */
@Service
class ProjectService(
    private val projects: ProjectRepository,
    private val repos: RepoRepository,
    private val memberships: MembershipRepository,
    private val serviceAccounts: ServiceAccountRepository,
    private val orgs: OrgService,
) {
    companion object {
        const val DEFAULT_PROJECT = "default"
        const val VISIBILITY_PRIVATE = "private"
        const val VISIBILITY_INTERNAL = "internal"
        const val VISIBILITY_PUBLIC = "public"
        val VISIBILITIES = setOf(VISIBILITY_PRIVATE, VISIBILITY_INTERNAL, VISIBILITY_PUBLIC)
        private val SLUG_PATTERN = Regex("^[a-z0-9-]{2,64}$")
    }

    fun get(id: Long): ProjectEntity? = projects.findById(id).orElse(null)

    fun getBySlug(
        orgId: Long,
        slug: String,
    ): ProjectEntity? = projects.findByOrgIdAndSlug(orgId, slug)

    fun list(orgId: Long): List<ProjectEntity> = projects.findAllByOrgIdOrderByIdAsc(orgId)

    /** 旧接口兼容：默认组织的项目列表（id + name）。 */
    fun listProjects(): List<ProjectRecord> =
        list(orgs.defaultOrgId()).map { project ->
            ProjectRecord(checkNotNull(project.id) { "project 必有 id" }, project.name)
        }

    /** 旧接口兼容：按项目名找 id（默认组织；先 slug，再退回原 name 查询）。 */
    fun projectIdByName(name: String): Long? {
        val orgId = orgs.defaultOrgId()
        return projects.findByOrgIdAndSlug(orgId, slugify(name))?.id ?: projects.findByName(name)?.id
    }

    /** 幂等确保默认组织的项目存在（旧调用方 / 启动初始化）。 */
    @Transactional
    fun ensureProject(name: String): Long = ensureProject(name, orgs.defaultOrgId())

    @Transactional
    fun ensureProject(
        name: String,
        orgId: Long,
    ): Long {
        val slug = slugify(name)
        projects.findByOrgIdAndSlug(orgId, slug)?.let { return checkNotNull(it.id) { "project 必有 id" } }
        val saved =
            projects.save(
                ProjectEntity(
                    orgId = orgId,
                    name = name,
                    slug = slug,
                    visibility = VISIBILITY_PRIVATE,
                    createdAt = Instant.now(),
                ),
            )
        return checkNotNull(saved.id) { "project 创建后应回填 id" }
    }

    @Transactional
    fun create(
        orgId: Long,
        name: String,
        slug: String? = null,
        description: String = "",
        visibility: String = VISIBILITY_PRIVATE,
        ownerId: Long? = null,
    ): ProjectEntity {
        require(name.isNotBlank()) { "项目名不能为空" }
        val resolvedSlug = normalizeSlug(slug ?: name)
        require(VISIBILITIES.contains(visibility)) { "visibility ∈ $VISIBILITIES" }
        require(projects.findByOrgIdAndSlug(orgId, resolvedSlug) == null) { "项目 slug $resolvedSlug 已存在" }
        if (ownerId != null) {
            require(orgs.membership(ownerId, orgId)?.status == OrgService.STATUS_ACTIVE) { "owner 必须是组织成员" }
        }
        val saved =
            projects.save(
                ProjectEntity(
                    orgId = orgId,
                    name = name.trim(),
                    slug = resolvedSlug,
                    description = description,
                    visibility = visibility,
                    ownerId = ownerId,
                    archived = false,
                    createdAt = Instant.now(),
                ),
            )
        if (ownerId != null) {
            ensureOwnerMembership(ownerId, checkNotNull(saved.id) { "project 创建后应回填 id" })
        }
        return saved
    }

    @Transactional
    fun update(
        id: Long,
        name: String? = null,
        slug: String? = null,
        description: String? = null,
        visibility: String? = null,
        archived: Boolean? = null,
    ): ProjectEntity {
        val project = projects.findById(id).orElse(null) ?: throw IllegalArgumentException("项目 $id 不存在")
        val orgId = checkNotNull(project.orgId) { "项目 $id 未归属组织" }
        val resolvedSlug = slug?.let(::normalizeSlug) ?: project.slug
        if (resolvedSlug != null && resolvedSlug != project.slug) {
            require(projects.findByOrgIdAndSlug(orgId, resolvedSlug) == null) { "项目 slug $resolvedSlug 已存在" }
        }
        if (visibility != null) require(VISIBILITIES.contains(visibility)) { "visibility ∈ $VISIBILITIES" }
        val updated =
            project.copy(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: project.name,
                slug = resolvedSlug,
                description = description ?: project.description,
                visibility = visibility ?: project.visibility,
                archived = archived ?: project.archived,
            )
        return projects.save(updated)
    }

    @Transactional
    fun archive(id: Long): ProjectEntity {
        val project = projects.findById(id).orElse(null) ?: throw IllegalArgumentException("项目 $id 不存在")
        return if (project.archived) project else projects.save(project.copy(archived = true))
    }

    @Transactional
    fun transferOwner(
        id: Long,
        ownerId: Long,
    ): ProjectEntity {
        val project = projects.findById(id).orElse(null) ?: throw IllegalArgumentException("项目 $id 不存在")
        val newOwnerMembership =
            memberships.findByUserIdAndProjectId(ownerId, id)
                ?: throw IllegalArgumentException("新 owner 必须是项目成员")
        val previousOwnerId = project.ownerId
        memberships.save(newOwnerMembership.copy(role = Role.OWNER))
        if (previousOwnerId != null && previousOwnerId != ownerId) {
            memberships.findByUserIdAndProjectId(previousOwnerId, id)?.let {
                memberships.save(it.copy(role = Role.ADMIN))
            }
        }
        return projects.save(project.copy(ownerId = ownerId))
    }

    /** 删除前必须已归档且无仓库；仓库由调用方先迁移/删除。 */
    @Transactional
    fun delete(id: Long) {
        val project = projects.findById(id).orElse(null) ?: throw IllegalArgumentException("项目 $id 不存在")
        check(project.archived) { "项目 $id 必须先归档再删除" }
        check(repos.countByProjectId(id) == 0L) { "项目 $id 仍有仓库，不能删除" }
        // 项目级服务账号随项目清理（FK RESTRICT 由 Service 显式编排）；令牌随即失效。
        serviceAccounts.deleteAllByProjectId(id)
        memberships.deleteAllByProjectId(id)
        projects.deleteById(id)
    }

    /** bootstrap admin 后把默认项目的 owner 补上（迁移时 admin 可能尚不存在）。 */
    @Transactional
    fun ensureDefaultProjectOwner(ownerId: Long) {
        val orgId = orgs.defaultOrgId()
        val project = projects.findByOrgIdAndSlug(orgId, DEFAULT_PROJECT) ?: return
        val projectId = checkNotNull(project.id) { "project 必有 id" }
        if (project.ownerId == null) projects.save(project.copy(ownerId = ownerId))
        ensureOwnerMembership(ownerId, projectId)
    }

    private fun ensureOwnerMembership(
        userId: Long,
        projectId: Long,
    ) {
        val existing = memberships.findByUserIdAndProjectId(userId, projectId)
        if (existing != null) {
            if (existing.role != Role.OWNER) memberships.save(existing.copy(role = Role.OWNER))
        } else {
            memberships.save(MembershipEntity(userId = userId, projectId = projectId, role = Role.OWNER))
        }
    }

    fun slugify(name: String): String {
        val slug =
            name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(64)
        return if (slug.length < 2) throw IllegalArgumentException("项目名无法生成合法 slug：$name") else slug
    }

    private fun normalizeSlug(raw: String): String {
        val slug = raw.trim().lowercase()
        require(SLUG_PATTERN.matches(slug)) { "slug 必须匹配 $SLUG_PATTERN（当前 $slug）" }
        return slug
    }
}
