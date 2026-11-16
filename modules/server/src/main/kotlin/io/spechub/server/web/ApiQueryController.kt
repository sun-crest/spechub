package io.spechub.server.web

import io.spechub.server.ingest.Ingestor
import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.ProjectRecord
import io.spechub.spec.index.SpecIndexStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 只读查询扩展 + 写回端点的装配（F 组 MCP 工具集的 HTTP 后端） */
@RestController
@RequestMapping("/api")
class ApiQueryController(
    private val index: SpecIndexStore,
    private val writer: SpecWriter,
    private val ingestor: Ingestor,
    private val urlTokens: UrlTokenStore,
    private val auth: AuthService,
) {

    // ── 只读 · F 组 list_projects / get_api ───────────────────────────────

    @GetMapping("/projects")
    fun projects(): List<ProjectRecord> = index.listProjects()

    @GetMapping("/apis")
    fun search(
        @RequestParam q: String? = null,
        @RequestParam method: String? = null,
        @RequestParam stage: String? = null,
    ): List<ApiRecord> = index.listAllApis(q, method?.uppercase(), stage ?: "draft")

    @GetMapping("/apis/{id}")
    fun getApi(@PathVariable id: Long): ApiRecord =
        index.getApiById(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "api $id 不存在")

    // ── 写回 · F 组 create_api / update_api（R1-C）─────────────────────────

    data class WriteSpecRequest(val path: String, val content: String)

    data class WriteSpecResponse(
        val repoId: Long,
        val path: String,
        val commit: String,
        val branch: String,
        val apiCount: Int,
        /** I8 评审流：启鉴权下写回先入评审队列，非空表示待批准；无鉴权直写时为 null */
        val reviewId: Long? = null,
        val status: String? = null,
        val message: String = "",
    )

    @GetMapping("/repos/{id}/specs")
    fun listSpecs(@PathVariable id: Long): List<String> {
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        return writer.listSpecs(repo)
    }

    @GetMapping("/repos/{id}/spec")
    fun readSpec(@PathVariable id: Long, @RequestParam path: String): String {
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        return writer.readSpec(repo, path)
            ?: throw ApiException(ApiErrorCode.NOT_FOUND, "spec $path 不存在")
    }

    @PutMapping("/repos/{id}/spec")
    fun writeSpec(@PathVariable id: Long, @RequestBody req: WriteSpecRequest, request: jakarta.servlet.http.HttpServletRequest): WriteSpecResponse {
        auth.requireRepoRole(request, id, io.spechub.spec.index.Role.EDITOR, "写回 spec")
        if (req.path.isBlank() || !(req.path.endsWith(".yaml") || req.path.endsWith(".yml") || req.path.endsWith(".json"))) {
            throw ApiException(ApiErrorCode.BAD_REQUEST, "path 须为 specs 下的 .yaml/.yml/.json 文件")
        }
        val repo = index.getRepo(id) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $id 不存在")
        val path = req.path.trim()

        // 未启用鉴权：本地/最小部署直写（保存即提交，现状；评审流只在团队启用鉴权后生效）
        if (!auth.authEnabled) {
            val written = writer.writeSpec(repo, path, req.content)
            val ing = ingestor.ingest(repo, urlTokens.get(id))
            return WriteSpecResponse(repo.id, path, written.commit, written.branch, ing.apiCount, message = "已提交 ${written.commit.take(8)}")
        }

        // 启鉴权 →「写操作皆审」（§6.4 决策 3）：先入评审队列，approve 后才落真源
        val actor = auth.currentUser(request)?.username ?: "anonymous"
        val review = index.createReview(
            repoId = id, path = path, newContent = req.content,
            diff = "", oldHash = index.latestSpecHash(id, path), requester = actor,
        )
        index.addChangelog(repoId = id, apiId = null, actor = actor, action = "review.create",
            detail = "提交评审 #${review.id}：$path（$actor 提交，等待审批）")
        return WriteSpecResponse(
            repo.id, path, commit = "", branch = repo.branch, apiCount = 0,
            reviewId = review.id, status = io.spechub.spec.index.ReviewStatus.PENDING,
            message = "已提交评审请求 #${review.id}：$path（写操作皆审，审批通过后落真源）",
        )
    }
}