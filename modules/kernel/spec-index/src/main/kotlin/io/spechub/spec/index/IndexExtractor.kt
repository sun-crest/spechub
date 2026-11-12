package io.spechub.spec.index

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.spechub.spec.model.Operation
import io.spechub.spec.model.OpenApiDoc

/**
 * 从归一化 OpenApiDoc 提取索引行（api / schema）。
 * 纯函数：输入 doc → 输出行列表，无 I/O；spec_hash 由外部传入（Fingerprint 职责在 spec-model）。
 */
object IndexExtractor {

    private val json: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    /** 每个 path+method → 一条 api 行。tags 序列化存 JSON 数组。 */
    fun apis(doc: OpenApiDoc, repoId: Long, specHash: String): List<IndexDb.ApiRow> =
        doc.paths.flatMap { (path, item) ->
            item.operations.map { (method, op) -> toApiRow(repoId, specHash, path, method.name, op) }
        }

    private fun toApiRow(repoId: Long, specHash: String, path: String, method: String, op: Operation): IndexDb.ApiRow =
        IndexDb.ApiRow(
            id = 0,
            repoId = repoId,
            specHash = specHash,
            path = path,
            method = method,
            operationId = op.operationId,
            summary = op.summary,
            tags = op.tags,
            specVersion = null,
        )

    /** components.schemas → schema 行；definition 存规格化的 schema JSON。 */
    fun schemas(doc: OpenApiDoc, repoId: Long, specHash: String): List<IndexDb.SchemaRow> =
        doc.components.schemas.map { (name, schema) ->
            IndexDb.SchemaRow(
                id = 0,
                repoId = repoId,
                specHash = specHash,
                name = name,
                definition = json.writeValueAsString(schema),
            )
        }
}