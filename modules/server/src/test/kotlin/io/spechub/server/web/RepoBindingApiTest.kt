package io.spechub.server.web

import io.spechub.server.repository.RepoBindingEntity
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoBindingService
import io.spechub.server.service.RepoService
import io.spechub.server.service.UserService
import io.spechub.spec.gstore.GitCli
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 A / ADR §4.4 —— 真源绑定 API：
 * - 注册仓库必须带 projectId，并自动创建首个 active binding；
 * - verify 三分支（no_active / identical / diverged）；
 * - activate：不一致需显式 confirm；flip 后旧 active → retired、单主保持、评论/订阅/评审不变；
 * - 并发 flip 只有一个成功（409）；
 * - retired 删除受回滚窗口限制。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
class RepoBindingApiTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var bindingService: RepoBindingService

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private const val ADMIN = "test-admin-token-123"
        private val TEMPDIR: Path = createTempDirectory("spechub-bindingapi-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_binding_api")
        }

        private val cli = GitCli()
        private val git = GitStore()

        /** 种一个 file:// 裸仓，返回 `file:///abs/path` 形式的 URL。 */
        private fun seedOrigin(path: String): String {
            val root = TEMPDIR.resolve("origin-${UUID.randomUUID()}")
            val bare = root.resolve("origin").also { Files.createDirectories(it) }
            cli.run(bare, listOf("init", "--bare", "-b", "main")).require("init bare")
            val work = root.resolve("seedwork")
            Files.createDirectories(work.resolve("specs/pet"))
            Files.writeString(work.resolve("specs/pet/openapi.yaml"), spec(path))
            cli.run(work, listOf("init", "-b", "main")).require("init")
            git.stage(work, listOf(".")).require("stage")
            git.commit(work, "seed", Identity("tester", "t@spechub.test")).require("commit")
            cli.run(work, listOf("remote", "add", "origin", bare.toAbsolutePath().toString())).require("add remote")
            cli.run(work, listOf("push", "origin", "main")).require("push")
            return "file://" + bare.toAbsolutePath()
        }

        private fun spec(path: String): String =
            """
            openapi: 3.1.0
            info: { title: Pet Store $path, version: "1.0.0" }
            paths:
              $path:
                get:
                  operationId: list${path.removePrefix("/").replaceFirstChar { it.uppercase() }}
                  responses: { "200": { description: ok } }
            """.trimIndent()
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun uniqueSuffix(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(12)

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String = ADMIN,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $token")
        if (body != null) {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun codeOf(body: String): String? = json.readTree(body).get("code")?.asText()

    private fun createProject(): Long {
        val resp = send("POST", "/api/projects", """{"name":"绑定测试","slug":"b-${uniqueSuffix()}"}""")
        assertEquals(201, resp.statusCode(), resp.body())
        return checkNotNull(json.readTree(resp.body()).get("id")).asLong()
    }

    private fun registerRepo(
        projectId: Long,
        url: String,
    ): Long {
        val resp =
            send(
                "POST",
                "/api/repos",
                """{"projectId":$projectId,"url":"$url","branch":"main"}""",
            )
        assertEquals(200, resp.statusCode(), resp.body())
        return checkNotNull(json.readTree(resp.body()).at("/repo/id")).asLong()
    }

    private fun createCandidate(
        repoId: Long,
        url: String,
    ): Long {
        val resp =
            send(
                "POST",
                "/api/repos/$repoId/bindings",
                """{"kind":"external","remoteUrl":"$url","branch":"main"}""",
            )
        assertEquals(201, resp.statusCode(), resp.body())
        return checkNotNull(json.readTree(resp.body()).get("id")).asLong()
    }

    private fun bindings(repoId: Long): List<JsonNode> {
        val resp = send("GET", "/api/repos/$repoId/bindings")
        assertEquals(200, resp.statusCode(), resp.body())
        val node = json.readTree(resp.body())
        return (0 until node.size()).map { checkNotNull(node.get(it)) }
    }

    private fun activeBindings(repoId: Long): List<JsonNode> = bindings(repoId).filter { it.at("/status").asText() == "active" }

    private fun count(
        table: String,
        repoId: Long,
    ): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE repo_id = ?", Int::class.java, repoId) ?: 0

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `注册仓库带 projectId - 归属正确且自动创建唯一 active binding`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))

        assertEquals(projectId, repoService.getRepo(repoId)?.projectId, "repo 归属指定项目")
        val all = bindings(repoId)
        assertEquals(1, all.size, "注册即一条 binding")
        assertEquals("active", all.single().at("/status").asText())
        assertEquals("managed", all.single().at("/kind").asText())
        assertTrue(all.single().at("/connectionId").isNull, "阶段 A connection 可空")
        assertEquals(1, activeBindings(repoId).size, "恰好一条 active")

        // 数据库单主约束：绕过 API 直插第二条 active 必须失败
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update(
                "INSERT INTO repo_binding (repo_id, kind, remote_url, branch, status) VALUES (?, 'managed', ?, 'main', 'active')",
                repoId,
                "file:///second-active-$repoId",
            )
        }
    }

    @Test
    fun `verify 三分支 - no_active identical diverged`() {
        val projectId = createProject()
        val originA = seedOrigin("/pets")
        val repoId = registerRepo(projectId, originA)
        assertNotNull(bindingService.active(repoId))

        // identical：同真源内容
        val same = createCandidate(repoId, originA)
        val sameResp = send("POST", "/api/repos/$repoId/bindings/$same/verify")
        assertEquals(200, sameResp.statusCode(), sameResp.body())
        assertEquals("identical", json.readTree(sameResp.body()).at("/status").asText())

        // diverged：不同真源（/pets → /cats）
        val other = createCandidate(repoId, seedOrigin("/cats"))
        val diffResp = send("POST", "/api/repos/$repoId/bindings/$other/verify")
        assertEquals(200, diffResp.statusCode(), diffResp.body())
        val diff = json.readTree(diffResp.body())
        assertEquals("diverged", diff.at("/status").asText())
        assertTrue(diff.at("/changed").size() >= 1, "同一文件内容变化应出现在 changed：${diffResp.body()}")

        // no_active：直接抹掉 active（模拟异常/迁移中间态）
        jdbc.update("DELETE FROM repo_binding WHERE repo_id = ? AND status = 'active'", repoId)
        val candidate = bindingService.createCandidate(repoId, RepoBindingEntity.KIND_MANAGED, originA)
        val noActive =
            send("POST", "/api/repos/$repoId/bindings/${assertNotNull(candidate.id)}/verify")
        assertEquals(200, noActive.statusCode(), noActive.body())
        assertEquals("no_active", json.readTree(noActive.body()).at("/status").asText())
    }

    @Test
    fun `activate - 不一致需 confirm 显式确认，flip 后单主且评论订阅评审不变`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))

        // flip 前写下评论 / 订阅 / 评审（管理数据只认 repo_id）
        val apis = json.readTree(send("GET", "/api/repos/$repoId/apis").body())
        val apiId = checkNotNull(apis.get(0)?.at("/id")?.asLong())
        assertEquals(200, send("POST", "/api/apis/$apiId/comments", """{"body":"迁移前评论"}""").statusCode())
        assertEquals(
            200,
            send("POST", "/api/subscriptions", """{"repoId":$repoId,"method":"GET","path":"/pets","team":"core"}""").statusCode(),
        )
        val put =
            send(
                "PUT",
                "/api/repos/$repoId/spec",
                """{"path":"specs/pet/openapi.yaml","content":"openapi: 3.1.0\ninfo: { title: v2, version: \"1.0.0\" }\npaths: {}\n"}""",
            )
        assertEquals(200, put.statusCode(), put.body())
        val commentsBefore = count("comment", repoId)
        val subsBefore = count("subscription", repoId)
        val reviewsBefore = count("review", repoId)
        assertTrue(commentsBefore > 0 && subsBefore > 0 && reviewsBefore > 0, "前置管理数据已就绪")

        val oldActiveId = assertNotNull(bindingService.active(repoId)?.id)
        val candidate = createCandidate(repoId, seedOrigin("/cats"))

        // 不一致且未确认 → 409
        val blocked = send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":false}""")
        assertEquals(409, blocked.statusCode(), blocked.body())
        assertEquals("CONFLICT", codeOf(blocked.body()))
        assertEquals(oldActiveId, bindingService.active(repoId)?.id, "未确认不得 flip")

        // 显式确认 → flip + 重建索引
        val activated = send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":true}""")
        assertEquals(200, activated.statusCode(), activated.body())
        assertEquals("active", json.readTree(activated.body()).at("/binding/status").asText())
        assertTrue(json.readTree(activated.body()).at("/ingestError").isNull, "flip 后应按新真源重建索引")

        val active = activeBindings(repoId)
        assertEquals(1, active.size, "仍恰好一条 active")
        assertEquals(candidate, active.single().at("/id").asLong())
        val retired = bindings(repoId).first { it.at("/id").asLong() == oldActiveId }
        assertEquals("retired", retired.at("/status").asText())
        assertTrue(retired.at("/deleteAfter").isTextual, "retired 带回滚窗口到期时间")

        // 索引已按新真源重建
        val newApis = json.readTree(send("GET", "/api/repos/$repoId/apis").body()).toString()
        assertTrue(newApis.contains("/cats"), "flip 后索引按新真源重建：$newApis")

        // 评论 / 订阅 / 评审不受 flip 影响
        assertEquals(commentsBefore, count("comment", repoId))
        assertEquals(subsBefore, count("subscription", repoId))
        assertEquals(reviewsBefore, count("review", repoId))
    }

    @Test
    fun `并发 flip - 只有一个成功，另一个 409`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))
        val first = createCandidate(repoId, seedOrigin("/cats"))
        val second = createCandidate(repoId, seedOrigin("/dogs"))

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                listOf(first, second).map { bindingId ->
                    pool.submit<Int> {
                        start.await(10, TimeUnit.SECONDS)
                        send("POST", "/api/repos/$repoId/bindings/$bindingId/activate", """{"confirm":true}""").statusCode()
                    }
                }
            start.countDown()
            val statuses = futures.map { it.get(60, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 409), statuses, "并发 flip 应一胜一冲突")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, activeBindings(repoId).size, "数据库单主约束兜底")
    }

    @Test
    fun `retired 删除受回滚窗口限制 - 窗口结束后可删`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))
        val oldActiveId = assertNotNull(bindingService.active(repoId)?.id)
        val candidate = createCandidate(repoId, seedOrigin("/cats"))
        assertEquals(200, send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":true}""").statusCode())

        val tooEarly = send("DELETE", "/api/repos/$repoId/bindings/$oldActiveId")
        assertEquals(409, tooEarly.statusCode(), tooEarly.body())
        assertEquals("CONFLICT", codeOf(tooEarly.body()))

        // 回拨退休时间到窗口之外（默认 30 天）
        jdbc.update(
            "UPDATE repo_binding SET retired_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.DAYS)),
            oldActiveId,
        )
        assertEquals(204, send("DELETE", "/api/repos/$repoId/bindings/$oldActiveId").statusCode())
        assertTrue(bindings(repoId).none { it.at("/id").asLong() == oldActiveId })
    }

    @Test
    fun `回滚窗口内可重新激活 retired binding`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))
        val originalId = assertNotNull(bindingService.active(repoId)?.id)
        val candidate = createCandidate(repoId, seedOrigin("/cats"))
        assertEquals(200, send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":true}""").statusCode())
        assertEquals(candidate, bindingService.active(repoId)?.id)

        val rollback = send("POST", "/api/repos/$repoId/bindings/$originalId/activate", """{"confirm":true}""")
        assertEquals(200, rollback.statusCode(), rollback.body())
        assertEquals(originalId, bindingService.active(repoId)?.id, "回滚后原真源重新 active")
        assertEquals("retired", bindings(repoId).first { it.at("/id").asLong() == candidate }.at("/status").asText())
        assertEquals(1, activeBindings(repoId).size)
    }

    @Test
    fun `binding 权限分层 - editor 不可管理，owner 可 flip`() {
        val projectId = createProject()
        val repoId = registerRepo(projectId, seedOrigin("/pets"))
        val suffix = uniqueSuffix()
        val editorToken = "bind-editor-token-$suffix"
        users.createUserWithMembership("bind-editor-$suffix", "", AuthService.sha256(editorToken), projectId, Role.EDITOR)
        val candidate = createCandidate(repoId, seedOrigin("/cats"))

        // editor 可读列表，但不能创建/切换绑定
        assertEquals(200, send("GET", "/api/repos/$repoId/bindings", token = editorToken).statusCode())
        assertEquals(
            403,
            send("POST", "/api/repos/$repoId/bindings", """{"remoteUrl":"${seedOrigin("/dogs")}"}""", editorToken).statusCode(),
        )
        assertEquals(
            403,
            send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":true}""", editorToken).statusCode(),
        )

        // 项目 owner（admin 用户）可 flip
        assertEquals(200, send("POST", "/api/repos/$repoId/bindings/$candidate/activate", """{"confirm":true}""").statusCode())
    }

    @Test
    fun `默认项目仍可注册 - 兼容 bootstrap`() {
        val defaultProjectId = projects.projectIdByName(ProjectService.DEFAULT_PROJECT) ?: error("default project 未初始化")
        val repoId = registerRepo(defaultProjectId, seedOrigin("/pets"))
        assertEquals(defaultProjectId, repoService.getRepo(repoId)?.projectId)
    }
}
