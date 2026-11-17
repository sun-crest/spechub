package io.spechub.server.web

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R11 —— 文档层验收（Knife4j Next 5.x + springdoc 3.x）：
 * - `spechub.openapi-public=true`（dev）时，/v3/api-docs、/doc.html、webjars 无需 token；
 * - 业务端点仍受 TokenAuthFilter 保护（白名单只覆盖文档路径）；
 * - api-docs 含 OpenApiConfig 的全局 info，且 paths 非空。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.admin-token=test-openapi-token",
        "spechub.openapi-public=true",
        "spechub.polling-interval-ms=3600000",
    ],
)
class OpenApiDocsTest : TestDbIsolation() {

    @LocalServerPort
    var port: Int = 0

    companion object {
        private val TEMPDIR: java.nio.file.Path =
            kotlin.io.path.createTempDirectory("spechub-openapi-test-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }

        private val json = JsonMapper.builder().build()
    }

    private val client = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `api-docs 与 Knife4j UI 无需 token 即可访问`() {
        val docs = get("/v3/api-docs")
        assertEquals(200, docs.statusCode(), "api-docs 应公开：${docs.body().take(200)}")
        val tree = json.readTree(docs.body())
        assertNotNull(tree.get("openapi"), "缺少 openapi 版本字段")
        assertTrue((tree.get("paths")?.size() ?: 0) > 0, "paths 不应为空")
        assertTrue(docs.body().contains("SpecHub API"), "应包含 OpenApiConfig 的全局 title")

        val ui = get("/doc.html")
        assertEquals(200, ui.statusCode())
        assertTrue(
            ui.headers().firstValue("content-type").orElse("").contains("text/html"),
            "doc.html 应为 HTML：${ui.headers().firstValue("content-type").orElse("(none)")}",
        )
        assertTrue(ui.body().contains("knife4j-ui-react"), "doc.html 应加载 Knife4j Next UI")

        val asset = get("/webjars/knife4j-ui-react/knife4j-next-mark.svg")
        assertEquals(200, asset.statusCode(), "webjars 静态资源应公开")
    }

    @Test
    fun `业务端点仍需要 token`() {
        val res = get("/api/repos")
        assertEquals(401, res.statusCode())
        assertTrue(res.body().contains("AUTH_UNAUTHORIZED"), "错误体应走统一契约：${res.body()}")
    }
}
