package io.spechub.server.web

import io.spechub.server.service.ChangelogService
import io.spechub.server.service.CommentService
import io.spechub.server.service.OrgService
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.ReviewService
import io.spechub.server.service.SubscriptionService
import io.spechub.server.service.UserService
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.DocRecord
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Role
import io.spechub.spec.index.Stage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A4 验收：两项目 + 一个只属于 A 的 viewer。
 * 列表 / 详情 / 搜索 / 导出 / 评论 / 评审 / 订阅 / mock 全部看不到 B。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.polling-interval-ms=3600000",
        "spechub.admin-token=test-admin-token-123",
    ],
)
class ReadIsolationTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var orgs: OrgService

    @Autowired
    lateinit var projects: ProjectService

    @Autowired
    lateinit var repos: RepoService

    @Autowired
    lateinit var users: UserService

    @Autowired
    lateinit var comments: CommentService

    @Autowired
    lateinit var reviewService: ReviewService

    @Autowired
    lateinit var subscriptions: SubscriptionService

    @Autowired
    lateinit var changelogService: ChangelogService

    @Autowired
    lateinit var projection: ProjectionStore

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-readiso-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_read_iso")
        }
    }

    private data class Fixture(
        val token: String,
        val projectA: Long,
        val projectB: Long,
        val repoA: Long,
        val repoB: Long,
        val apiA: Long,
        val apiB: Long,
        val docA: Long,
        val docB: Long,
        val reviewB: Long,
        val subscriptionB: Long,
    )

    private fun fixture(): Fixture {
        val suffix =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val orgId = orgs.defaultOrgId()
        val projectA = projects.create(orgId, "项目 A $suffix", "a-$suffix", visibility = "private")
        val projectB = projects.create(orgId, "项目 B $suffix", "b-$suffix", visibility = "private")
        val projectAId = assertNotNull(projectA.id)
        val projectBId = assertNotNull(projectB.id)
        val repoA = repos.register(projectAId, "file:///read-iso-a-$suffix", "main").id
        val repoB = repos.register(projectBId, "file:///read-iso-b-$suffix", "main").id

        projection.replaceProjectionForRepo(
            repoId = repoA,
            apis = listOf(ApiRecord(repoId = repoA, method = "GET", path = "/pets", summary = "A iso api", specJson = "{}", stage = Stage.DRAFT)),
            schemas = emptyList(),
            docs = listOf(DocRecord(repoId = repoA, path = "docs/a.md", title = "A iso doc", markdown = "iso A")),
        )
        projection.replaceProjectionForRepo(
            repoId = repoB,
            apis = listOf(ApiRecord(repoId = repoB, method = "GET", path = "/pets", summary = "B iso api", specJson = "{}", stage = Stage.DRAFT)),
            schemas = emptyList(),
            docs = listOf(DocRecord(repoId = repoB, path = "docs/b.md", title = "B iso doc", markdown = "iso B")),
        )

        val apiA = assertNotNull(projection.listApis(repoA).single().id)
        val apiB = assertNotNull(projection.listApis(repoB).single().id)

        // 内部数据：评论 / 评审 / 订阅 / 通知 / 变更记录（只认 repo_id，隔离靠读端点过滤）
        comments.add(repoA, apiA, null, "GET", "/pets", "author-a", "A 评论")
        comments.add(repoB, apiB, null, "GET", "/pets", "author-b", "B 评论")
        val reviewB = assertNotNull(reviewService.create(repoB, "specs/b.yaml", "openapi: 3.1.0", "", null, "author-b").id)
        val subB = subscriptions.subscribe(repoB, apiB, "GET", "/pets", "team-b", "")
        val subscriptionB = assertNotNull(subB.id)
        subscriptions.addNotification(checkNotNull(subB.teamId), "team-b", repoB, "GET", "/pets", "B 通知")
        changelogService.add(repoB, apiB, "author-b", "comment", "B 变更记录")

        val rawToken = "user-token-$suffix"
        val username = "reader-$suffix"
        users.createUserWithMembership(username, "", AuthService.sha256(rawToken), projectAId, Role.VIEWER)

        return Fixture(
            token = rawToken,
            projectA = projectAId,
            projectB = projectBId,
            repoA = repoA,
            repoB = repoB,
            apiA = apiA,
            apiB = apiB,
            docA = assertNotNull(projection.listDocs(repoA).single().id),
            docB = assertNotNull(projection.listDocs(repoB).single().id),
            reviewB = reviewB,
            subscriptionB = subscriptionB,
        )
    }

    private fun send(
        method: String,
        path: String,
        token: String,
        body: String? = null,
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

    private fun get(
        path: String,
        token: String,
    ): HttpResponse<String> = send("GET", path, token)

    private fun post(
        path: String,
        token: String,
        body: String,
    ): HttpResponse<String> = send("POST", path, token, body)

    private fun codeOf(body: String): String? = json.readTree(body).get("code")?.asText()

    private fun idsOf(
        body: String,
        field: String,
    ): List<Long> {
        val node = json.readTree(body)
        return (0 until node.size()).map { node.get(it).get(field).asLong() }
    }

    @Test
    fun `只属于 A 的 viewer 在所有读端点看不到 B`() {
        val f = fixture()

        val projectsResp = get("/api/projects", f.token)
        assertEquals(200, projectsResp.statusCode(), projectsResp.body())
        val projectIds = idsOf(projectsResp.body(), "id")
        assertTrue(projectIds.contains(f.projectA))
        assertTrue(!projectIds.contains(f.projectB), "项目列表不应包含 B")

        val reposResp = get("/api/repos", f.token)
        assertEquals(200, reposResp.statusCode(), reposResp.body())
        val repoIds = idsOf(reposResp.body(), "id")
        assertTrue(repoIds.contains(f.repoA))
        assertTrue(!repoIds.contains(f.repoB), "仓库列表不应包含 B")

        val apisResp = get("/api/apis?q=iso", f.token)
        assertEquals(200, apisResp.statusCode(), apisResp.body())
        val apiRepoIds = idsOf(apisResp.body(), "repoId")
        assertEquals(listOf(f.repoA), apiRepoIds, "搜索只应命中 A")

        val docsResp = get("/api/docs", f.token)
        assertEquals(200, docsResp.statusCode(), docsResp.body())
        val docRepoIds = idsOf(docsResp.body(), "repoId")
        assertEquals(listOf(f.repoA), docRepoIds, "文档列表只应包含 A")

        val docSearchResp = get("/api/docs?q=iso", f.token)
        assertEquals(200, docSearchResp.statusCode(), docSearchResp.body())
        assertEquals(listOf(f.repoA), idsOf(docSearchResp.body(), "repoId"), "文档检索只应命中 A")

        // A8：按项目过滤（?projectId=）——A 正常，B 统一 404
        val reposInA = get("/api/repos?projectId=${f.projectA}", f.token)
        assertEquals(200, reposInA.statusCode(), reposInA.body())
        assertEquals(listOf(f.repoA), idsOf(reposInA.body(), "id"), "按项目 A 过滤只返回 A 的仓库")
        val apisInA = get("/api/apis?q=iso&projectId=${f.projectA}", f.token)
        assertEquals(200, apisInA.statusCode(), apisInA.body())
        assertEquals(listOf(f.repoA), idsOf(apisInA.body(), "repoId"), "按项目 A 过滤只返回 A 的接口")
        val docsInA = get("/api/docs?projectId=${f.projectA}", f.token)
        assertEquals(200, docsInA.statusCode(), docsInA.body())
        assertEquals(listOf(f.repoA), idsOf(docsInA.body(), "repoId"), "按项目 A 过滤只返回 A 的文档")
        assertEquals(404, get("/api/repos?projectId=${f.projectB}", f.token).statusCode(), "按不可见项目过滤应 404")
        assertEquals(404, get("/api/apis?projectId=${f.projectB}", f.token).statusCode(), "按不可见项目过滤应 404")
        assertEquals(404, get("/api/docs?projectId=${f.projectB}", f.token).statusCode(), "按不可见项目过滤应 404")

        assertEquals(404, get("/api/repos/${f.repoB}", f.token).statusCode(), "B 仓库详情应 404")
        assertEquals(404, get("/api/repos/${f.repoB}/apis", f.token).statusCode(), "B 接口列表应 404")
        assertEquals(404, get("/api/apis/${f.apiB}", f.token).statusCode(), "B 接口详情应 404")
        assertEquals(404, get("/api/docs/${f.docB}", f.token).statusCode(), "B 文档详情应 404")
        assertEquals(404, get("/api/repos/${f.repoB}/export/openapi31?stage=draft", f.token).statusCode(), "B 导出应 404")
        assertEquals(404, get("/api/repos/${f.repoB}/changelog", f.token).statusCode(), "B 变更记录应 404")
        assertEquals(404, get("/api/reviews?repoId=${f.repoB}", f.token).statusCode(), "B 评审队列应 404")
        assertEquals(404, get("/api/subscriptions?repoId=${f.repoB}", f.token).statusCode(), "B 订阅列表应 404")

        // 评论 / 评审 / 订阅 / 通知 / 变更记录：内部视图同样不得出现 B
        assertEquals(404, get("/api/apis/${f.apiB}/comments", f.token).statusCode(), "B 评论列表应 404")
        assertEquals(404, post("/api/apis/${f.apiB}/comments", f.token, """{"body":"越权评论"}""").statusCode(), "B 评论写入应 404")
        assertEquals(404, get("/api/reviews/${f.reviewB}", f.token).statusCode(), "B 评审详情应 404")
        assertEquals(0, json.readTree(get("/api/reviews", f.token).body()).size(), "评审列表不含 B")
        assertEquals(0, json.readTree(get("/api/subscriptions", f.token).body()).size(), "订阅列表不含 B")
        assertEquals(404, send("DELETE", "/api/subscriptions/${f.subscriptionB}", f.token).statusCode(), "取消 B 订阅应 404")
        assertEquals(0, json.readTree(get("/api/subscriptions/notifications?team=team-b", f.token).body()).size(), "通知不含 B")
        assertTrue(get("/api/changelog", f.token).body().let { !it.contains("B 变更记录") }, "变更记录不含 B")

        val commentsA = get("/api/apis/${f.apiA}/comments", f.token)
        assertEquals(200, commentsA.statusCode(), commentsA.body())
        assertTrue(commentsA.body().contains("A 评论"), "A 自身评论可读")

        val mockB = get("/mock/${f.repoB}/pets", f.token)
        assertEquals(404, mockB.statusCode())
        assertEquals("MOCK_NO_MATCH", codeOf(mockB.body()), "B mock 不应暴露存在性")

        // A 自身可正常读取
        assertEquals(200, get("/api/repos/${f.repoA}", f.token).statusCode())
        assertEquals(200, get("/api/apis/${f.apiA}", f.token).statusCode())
        assertEquals(200, get("/api/docs/${f.docA}", f.token).statusCode())

        // 多 repo 派生查询路径（评审/订阅/通知/变更记录）在可见 repo 集合上正常执行
        assertEquals(200, get("/api/reviews", f.token).statusCode())
        assertEquals(200, get("/api/subscriptions", f.token).statusCode())
        assertEquals(200, get("/api/subscriptions/notifications", f.token).statusCode())
        assertEquals(200, get("/api/changelog", f.token).statusCode())
    }
}
