package io.spechub.server.web

import io.spechub.server.repository.ProjectEntity
import io.spechub.server.service.AuditService
import io.spechub.server.service.ChangelogService
import io.spechub.server.service.ProjectService
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * 阶段 A —— 项目空间管理（ADR §4.2 / §4.3）：
 * - GET    /api/projects                 可见项目列表
 * - GET    /api/projects/{id}            项目详情（可见项目）
 * - POST   /api/projects                 创建（org_admin）
 * - PATCH  /api/projects/{id}            修改名称/slug/描述/可见性/归档（owner；仅可见性可 admin）
 * - POST   /api/projects/{id}/archive    归档（owner）
 * - POST   /api/projects/{id}/transfer   转移 owner（owner）
 * - DELETE /api/projects/{id}            删除（owner；须已归档且无仓库）
 */
@RestController
@RequestMapping("/api/projects")
class ProjectController(
    private val projects: ProjectService,
    private val auth: AuthService,
    private val changelogService: ChangelogService,
    private val audit: AuditService,
) {
    data class ProjectView(
        val id: Long,
        val name: String,
        val slug: String?,
        val description: String,
        val visibility: String,
        val ownerId: Long?,
        val archived: Boolean,
        val createdAt: String?,
    )

    data class CreateProjectRequest(
        @field:NotBlank val name: String,
        val slug: String? = null,
        val description: String = "",
        val visibility: String = ProjectService.VISIBILITY_PRIVATE,
        val ownerId: Long? = null,
    )

    data class UpdateProjectRequest(
        val name: String? = null,
        val slug: String? = null,
        val description: String? = null,
        val visibility: String? = null,
        val archived: Boolean? = null,
    )

    data class TransferOwnerRequest(
        val ownerId: Long,
    )

    @GetMapping
    fun list(request: HttpServletRequest): List<ProjectView> = auth.visibleProjects(request).map(::toView)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ProjectView = toView(auth.requireProjectVisible(request, id, Stage.RELEASE))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody @Valid body: CreateProjectRequest,
        request: HttpServletRequest,
    ): ProjectView {
        val actor = auth.requireOrgAdmin(request, "创建项目")
        val orgId = auth.currentOrgId(request)
        val ownerId = body.ownerId ?: actor.id?.takeIf { it > 0 }
        val project =
            domain {
                projects.create(
                    orgId = orgId,
                    name = body.name,
                    slug = body.slug,
                    description = body.description,
                    visibility = body.visibility,
                    ownerId = ownerId,
                )
            }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = actor.username,
            action = "project.create",
            detail = "创建项目 ${project.name}（${project.slug}）",
            orgId = orgId,
        )
        audit.recordCurrent(orgId, "project.create", "project", project.id?.toString(), "创建项目 ${project.name}（${project.slug}）")
        return toView(project)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: UpdateProjectRequest,
        request: HttpServletRequest,
    ): ProjectView {
        val visibilityOnly =
            body.visibility != null &&
                body.name == null &&
                body.slug == null &&
                body.description == null &&
                body.archived == null
        auth.requireProjectRole(request, id, if (visibilityOnly) Role.ADMIN else Role.OWNER, "修改项目")
        val updated =
            domain {
                projects.update(
                    id = id,
                    name = body.name,
                    slug = body.slug,
                    description = body.description,
                    visibility = body.visibility,
                    archived = body.archived,
                )
            }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(request),
            action = "project.update",
            detail = "更新项目 ${updated.name}（${updated.slug}）",
            orgId = updated.orgId ?: auth.currentOrgId(request),
        )
        audit.recordCurrent(
            updated.orgId ?: auth.currentOrgId(request),
            "project.update",
            "project",
            id.toString(),
            "更新项目 ${updated.name}（${updated.slug}）",
        )
        return toView(updated)
    }

    @PostMapping("/{id}/archive")
    fun archive(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ): ProjectView {
        auth.requireProjectRole(request, id, Role.OWNER, "归档项目")
        val project = domain { projects.archive(id) }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(request),
            action = "project.archive",
            detail = "归档项目 ${project.name}",
            orgId = project.orgId ?: auth.currentOrgId(request),
        )
        audit.recordCurrent(
            project.orgId ?: auth.currentOrgId(request),
            "project.archive",
            "project",
            id.toString(),
            "归档项目 ${project.name}",
        )
        return toView(project)
    }

    @PostMapping("/{id}/transfer")
    fun transferOwner(
        @PathVariable id: Long,
        @RequestBody @Valid body: TransferOwnerRequest,
        request: HttpServletRequest,
    ): ProjectView {
        auth.requireProjectRole(request, id, Role.OWNER, "转移项目 owner")
        val project = domain { projects.transferOwner(id, body.ownerId) }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(request),
            action = "project.transfer",
            detail = "转移项目 ${project.name} owner → 用户 ${body.ownerId}",
            orgId = project.orgId ?: auth.currentOrgId(request),
        )
        audit.recordCurrent(
            project.orgId ?: auth.currentOrgId(request),
            "project.transfer",
            "project",
            id.toString(),
            "转移项目 ${project.name} owner → 用户 ${body.ownerId}",
        )
        return toView(project)
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: Long,
        request: HttpServletRequest,
    ) {
        auth.requireProjectRole(request, id, Role.OWNER, "删除项目")
        val project =
            projects.get(id)
                ?: throw ApiException(ApiErrorCode.NOT_FOUND, "project $id 不存在")
        domain { projects.delete(id) }
        changelogService.add(
            repoId = null,
            apiId = null,
            actor = auth.currentActor(request),
            action = "project.delete",
            detail = "删除项目 ${project.name}",
            orgId = project.orgId ?: auth.currentOrgId(request),
        )
        audit.recordCurrent(
            project.orgId ?: auth.currentOrgId(request),
            "project.delete",
            "project",
            id.toString(),
            "删除项目 ${project.name}",
        )
    }

    private fun toView(project: ProjectEntity): ProjectView =
        ProjectView(
            id = checkNotNull(project.id) { "project 必有 id" },
            name = project.name,
            slug = project.slug,
            description = project.description,
            visibility = project.visibility,
            ownerId = project.ownerId,
            archived = project.archived,
            createdAt = project.createdAt.toString(),
        )

    /** 领域校验异常 → 统一 API 错误码（服务层不依赖 web）。 */
    private inline fun <T> domain(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            val code = if (e.message?.contains("已存在") == true) ApiErrorCode.CONFLICT else ApiErrorCode.VALIDATION
            throw ApiException(code, e.message ?: "参数不合法")
        } catch (e: IllegalStateException) {
            throw ApiException(ApiErrorCode.CONFLICT, e.message ?: "状态冲突")
        }
}
