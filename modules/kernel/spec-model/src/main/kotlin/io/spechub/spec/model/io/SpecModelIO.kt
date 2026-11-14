package io.spechub.spec.model.io

import io.spechub.spec.model.Components
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.Schema
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/*
 * SpecHub kernel · spec-model io —— OpenAPI 3.1 模型与 JSON/YAML 之间互访（开发计划 B2）。
 *
 * - Jackson 3（tools.jackson）单运行时：JSON 走 JsonMapper、YAML 走 YAMLMapper，均注册 KotlinModule。
 * - 单/多文档：JSON 为单文档；YAML 支持 `---` 分隔的多文档流，读取时逐文档归一化入模型。
 * - 全部走「tree → model」（convertValue），统一经过模型注解（type 数组、$ref 等），
 *   并保留原始 JsonNode 树给 spec-parser（C 组）做版本探测与升级映射。
 */

enum class SpecFormat { JSON, YAML }

/** additionalProperties 的二义值拆解：布尔（禁止/允许）或子 Schema */
sealed interface AdditionalPropertiesValue {
    /** `additionalProperties: true|false`（false = 禁止额外成员，有语义，勿归一化丢弃） */
    data class Allowed(val value: Boolean) : AdditionalPropertiesValue

    /** `additionalProperties: {…}` 子 Schema */
    data class ObjectSchema(val schema: Schema) : AdditionalPropertiesValue
}

object SpecModelIO {

    private val jsonMapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    private val yamlMapper: YAMLMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .build()

    /** 按首个非空白字符粗判格式：`{`/`[` → JSON，否则 YAML（YAML 是 JSON 超集，空串视为 YAML） */
    fun detectFormat(text: String): SpecFormat {
        val head = text.trimStart().take(1)
        return if (head == "{" || head == "[") SpecFormat.JSON else SpecFormat.YAML
    }

    fun readDocument(text: String, format: SpecFormat = detectFormat(text)): OpenApiDoc =
        openApiFromTree(parseToTree(text, format))

    /** 读入 YAML 多文档流（JSON 仅单文档）中的全部非空文档 */
    fun readDocuments(text: String, format: SpecFormat = detectFormat(text)): List<OpenApiDoc> {
        val nodes = when (format) {
            SpecFormat.JSON -> listOf(parseToTree(text, SpecFormat.JSON))
            SpecFormat.YAML -> {
                val iter = yamlMapper.readerFor(JsonNode::class.java).readValues<JsonNode>(text)
                buildList { while (iter.hasNext()) add(iter.next()) }
            }
        }
        return nodes.filter { !it.isNull }.map { openApiFromTree(it) }
    }

    fun writeDocument(doc: OpenApiDoc, format: SpecFormat, pretty: Boolean = true): String {
        val mapper = if (format == SpecFormat.JSON) jsonMapper else yamlMapper
        val writer = if (pretty) mapper.writerWithDefaultPrettyPrinter() else mapper.writer()
        return writer.writeValueAsString(doc)
    }

    /** 写 YAML 多文档流（以 `---` 分隔；JSON 仅单文档，取首个） */
    fun writeDocuments(docs: List<OpenApiDoc>, pretty: Boolean = true): String =
        docs.joinToString("\n---\n") { writeDocument(it, SpecFormat.YAML, pretty) }

    /** 底层树解析：spec-parser（C 组）的版本探测 / 升级映射入口 */
    fun parseToTree(text: String, format: SpecFormat = detectFormat(text)): JsonNode {
        val mapper = if (format == SpecFormat.JSON) jsonMapper else yamlMapper
        return mapper.readTree(text)
    }

    /** 模型 → JsonNode 树（B3 指纹复用）；null 输入也会产出 NullNode，不会返回 null */
    fun valueToTree(doc: Any?): JsonNode = jsonMapper.valueToTree<JsonNode>(doc)

    /** 任意 JsonNode → 目标模型（含 type 数组 / $ref 等注解语义） */
    fun <T> toModel(node: JsonNode, type: Class<T>): T = jsonMapper.convertValue(node, type)

    fun openApiFromTree(node: JsonNode): OpenApiDoc = toModel(node, OpenApiDoc::class.java)

    /** 拆解 additionalProperties 的布尔 / Schema 二义值 */
    fun additionalPropertiesOf(node: JsonNode?): AdditionalPropertiesValue? = when {
        node == null -> null
        node.isBoolean -> AdditionalPropertiesValue.Allowed(node.booleanValue())
        else -> AdditionalPropertiesValue.ObjectSchema(toModel(node, Schema::class.java))
    }

    /** 便捷：读出一个 Components 里的全部 schema（保持组件名顺序） */
    fun schemasOf(components: Components?): Map<String, Schema> = components?.schemas ?: emptyMap()
}