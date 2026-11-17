package io.spechub.server.web

import io.spechub.server.web.common.ApiErrorCode
import io.spechub.server.web.common.ApiException
import io.spechub.server.service.RepoService
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.Stage
import io.spechub.spec.model.Components
import io.spechub.spec.model.Info
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.Operation
import io.spechub.spec.model.PathItem
import io.spechub.spec.model.Schema
import io.spechub.spec.model.io.SpecModelIO
import org.springframework.stereotype.Service

/**
 * J3/J4 —— 导出底座：把 repo 当前档位（draft/release）的索引投影重建为标准 OpenApiDoc，
 * 供 3.1 / 3.0 降级 / Postman / 后续 Markdown-HTML 门户使用。
 * §6.4 决策 5：消费端默认读 release——导出 `stage=release` 即对外发布快照。
 */
@Service
class ExportService(private val projection: ProjectionStore, private val repoService: RepoService) {

    fun reconstructOpenApi(repoId: Long, stage: String = Stage.DRAFT): OpenApiDoc {
        val repo = repoService.getRepo(repoId) ?: throw ApiException(ApiErrorCode.NOT_FOUND, "repo $repoId 不存在")

        val paths = LinkedHashMap<String, PathItem>()
        for (api in projection.listApis(repoId, stage = stage)) {
            val op = try {
                SpecModelIO.toModel(SpecModelIO.parseToTree(api.specJson), Operation::class.java)
            } catch (e: Exception) {
                continue // 单条投影损坏不拖垮导出；reindex 纪律可修复
            }
            val item = paths.getOrPut(api.path) { PathItem() }
            paths[api.path] = setOperation(item, api.method.lowercase(), op)
        }

        val schemas = projection.listSchemas(repoId, stage = stage).associate { s ->
            val schema = try {
                SpecModelIO.toModel(SpecModelIO.parseToTree(s.specJson), Schema::class.java)
            } catch (e: Exception) {
                Schema()
            }
            s.name to schema
        }

        val orgRepo = repo.url.removeSuffix(".git").substringAfterLast("/")
        val info = Info(
            title = orgRepo.ifBlank { "repo#$repoId" },
            version = "1.0.0",
            description = "由 SpecHub 导出（repo=$repoId · stage=$stage）",
        )
        return OpenApiDoc(openapi = "3.1.0", info = info, paths = paths, components = Components(schemas = schemas))
    }

    private fun setOperation(item: PathItem, method: String, op: Operation): PathItem = when (method) {
        "get" -> item.copy(get = op)
        "put" -> item.copy(put = op)
        "post" -> item.copy(post = op)
        "delete" -> item.copy(delete = op)
        "options" -> item.copy(options = op)
        "head" -> item.copy(head = op)
        "patch" -> item.copy(patch = op)
        "trace" -> item.copy(trace = op)
        else -> item
    }
}