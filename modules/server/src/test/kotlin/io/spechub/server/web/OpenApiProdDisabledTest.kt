package io.spechub.server.web

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * R11 —— 生产 profile 的文档策略：
 * `spechub.openapi-public=false` + `springdoc.api-docs.enabled=false` + `knife4j.enable=false`，
 * 文档路径必须被 TokenAuthFilter 拒绝（401），而不是暴露给匿名访问者。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spechub.admin-token=prod-openapi-token",
        "spechub.polling-interval-ms=3600000",
    ],
)
@ActiveProfiles("prod")
class OpenApiProdDisabledTest : TestDbIsolation() {

    @LocalServerPort
    var port: Int = 0

    companion object {
        private val TEMPDIR: java.nio.file.Path =
            kotlin.io.path.createTempDirectory("spechub-openapi-prod-test-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun registerTempDirs(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
        }
    }

    private val client = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `生产 profile 不公开文档端点`() {
        assertEquals(401, get("/v3/api-docs").statusCode(), "prod api-docs 不应匿名可读")
        assertEquals(401, get("/doc.html").statusCode(), "prod Knife4j UI 不应匿名可读")
        assertEquals(200, get("/actuator/health").statusCode(), "actuator 白名单不受影响")
    }
}
