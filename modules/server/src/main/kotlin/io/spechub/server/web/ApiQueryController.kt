package io.spechub.server.web

import io.spechub.server.store.IndexService
import org.springframework.web.bind.annotation.*

/** 消费端查询（供 Web / MCP / CLI 读）：只读索引，不触碰 gitsource。 */
@RestController
@RequestMapping("/api")
class ApiQueryController(private val index: IndexService) {

    @GetMapping("/projects/{project}/repos/{org}/{repo}/apis")
    fun listApis(
        @PathVariable project: String,
        @PathVariable org: String,
        @PathVariable repo: String,
    ): Map<String, Any> {
        val rows = index.store.listApis(project, org, repo)
        return mapOf(
            "project" to project,
            "repo" to "$org/$repo",
            "count" to rows.size,
            "apis" to rows.map { row ->
                mapOf(
                    "id" to row.id,
                    "path" to row.path,
                    "method" to row.method,
                    "operationId" to row.operationId,
                    "summary" to row.summary,
                    "tags" to row.tags,
                    "specVersion" to row.specVersion,
                    "specHash" to row.specHash,
                )
            },
        )
    }

    @GetMapping("/projects/{project}/repos/{org}/{repo}/schemas")
    fun listSchemas(
        @PathVariable project: String,
        @PathVariable org: String,
        @PathVariable repo: String,
    ): Map<String, Any> = mapOf("schemas" to index.store.listSchemas(project, org, repo))

    @GetMapping("/projects/{project}/repos/{org}/{repo}/versions")
    fun versions(
        @PathVariable project: String,
        @PathVariable org: String,
        @PathVariable repo: String,
    ): Map<String, Any> = mapOf("versions" to index.store.versionHistory(project, org, repo))
}