package io.spechub.server.ingest

import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.SchemaRecord
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.io.SpecModelIO

/*
 * E3 —— 归一化模型 → 索引投影映射。
 * spec_json 列存各对象树化为 JSON（compact）；schema 的 $ref 文本保留，解引用由消费端解析。
 */

object IndexMapper {

    fun extractApis(repoId: Long, versionId: Long, doc: OpenApiDoc): List<ApiRecord> =
        doc.paths.flatMap { (path, item) ->
            item.operations().map { (method, op) ->
                ApiRecord(
                    repoId = repoId,
                    versionId = versionId,
                    method = method.uppercase(),
                    path = path,
                    operationId = op.operationId,
                    tag = op.tags.firstOrNull(),
                    summary = op.summary,
                    specJson = SpecModelIO.valueToTree(op).toString(),
                )
            }
        }

    fun extractSchemas(repoId: Long, versionId: Long, doc: OpenApiDoc): List<SchemaRecord> =
        doc.components?.schemas?.map { (name, schema) ->
            SchemaRecord(
                repoId = repoId,
                versionId = versionId,
                name = name,
                specJson = SpecModelIO.valueToTree(schema).toString(),
            )
        } ?: emptyList()
}