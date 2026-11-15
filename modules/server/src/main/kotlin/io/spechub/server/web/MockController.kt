package io.spechub.server.web

import io.spechub.spec.index.MockRuleRecord
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.mock.MockEngine
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/*
 * I2/I3 —— Mock 并入 backend（MVC + Java 25 虚拟线程，§7 选型；不引 WebFlux）。
 *
 * 两个面：
 * 1) 真实 mock 服务（`/mock/{repoId}/` 下任意路径）：任意 HTTP 方法命中 → MockEngine 按 spec 生成样例（release 优先）。
 *    「mock 与发布契约一致」：只读投影生成、不等同管理写操作，鉴权放行（联调本地服务无平台凭据）。
 * 2) 规则与预览 `/api/...`：mock_rule 的存放/查询（value / pattern / script 三档）+ 详情页 mock 预览。
 */
@RestController
class MockController(
    private val engine: MockEngine,
    private val index: SpecIndexStore,
) {

    // ── 真实 mock 服务：/mock/{repoId}/{spec 路径} ───────────────────────────────

    @RequestMapping("/mock/{repoId}/**")
    fun mock(@PathVariable repoId: Long, request: HttpServletRequest): ResponseEntity<String> {
        val prefix = "/mock/$repoId"
        val path = request.requestURI.substringAfter(prefix).ifBlank { "/" }
        val method = request.method ?: "GET"
        val result = engine.generate(repoId, method, path)
        return if (result == null) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"repo $repoId 无匹配 $method $path 的接口"}""")
        } else {
            ResponseEntity.status(result.response.status)
                .contentType(MediaType.parseMediaType(result.response.mediaType.ifBlank { "application/json" }))
                .header("X-Mock-Stage", result.stage)
                .header("X-Mock-Api-Id", result.apiId?.toString() ?: "")
                .body(result.response.body)
        }
    }

    // ── 规则 CRUD（I2 value / pattern / script 三档；规则 UI 编辑见后续批次）──────

    data class RuleRequest(
        val fieldPattern: String,
        val ruleType: String,
        val ruleValue: String = "",
        val apiId: Long? = null,
    )

    @GetMapping("/api/repos/{id}/mock/rules")
    fun rules(@PathVariable id: Long): List<MockRuleRecord> {
        index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")
        return index.listMockRules(id)
    }

    @PostMapping("/api/repos/{id}/mock/rules")
    fun addRule(@PathVariable id: Long, @RequestBody req: RuleRequest): MockRuleRecord {
        if (req.fieldPattern.isBlank() || !VALID_TYPES.contains(req.ruleType)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fieldPattern 非空；ruleType ∈ value/pattern/script")
        }
        index.getRepo(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "repo $id 不存在")
        val rule = index.upsertMockRule(
            MockRuleRecord(repoId = id, apiId = req.apiId, fieldPattern = req.fieldPattern.trim(), ruleType = req.ruleType, ruleValue = req.ruleValue),
        )
        index.addChangelog(repoId = id, apiId = req.apiId, actor = "spechub-bot", action = "mock.rule",
            detail = "Mock 规则 [${req.ruleType}] ${req.fieldPattern} = ${req.ruleValue.take(60)}")
        return rule
    }

    @DeleteMapping("/api/mock/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRule(@PathVariable ruleId: Long) {
        index.deleteMockRule(ruleId)
    }

    // ── 详情页 mock 预览（不改真源，直接生成一版样例给前端看）────────────────────

    data class MockPreview(
        val method: String,
        val requestPath: String,
        val stage: String,
        val status: Int,
        val mediaType: String,
        val body: String,
        val mockUrl: String,
    )

    @GetMapping("/api/apis/{id}/mock/preview")
    fun preview(@PathVariable id: Long): MockPreview {
        val api = index.getApiById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "api $id 不存在")
        val result = engine.generate(api.repoId, api.method, api.path)
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "mock 生成失败（spec 无响应 schema？）")
        return MockPreview(
            method = api.method, requestPath = api.path, stage = result.stage,
            status = result.response.status, mediaType = result.response.mediaType, body = result.response.body,
            mockUrl = "/mock/${api.repoId}${api.path}",
        )
    }

    companion object {
        private val VALID_TYPES = setOf("value", "pattern", "script")
    }
}