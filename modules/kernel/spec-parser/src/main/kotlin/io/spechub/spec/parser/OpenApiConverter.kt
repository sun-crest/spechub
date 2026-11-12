package io.spechub.spec.parser

import io.spechub.spec.model.Components
import io.spechub.spec.model.Example
import io.spechub.spec.model.HttpMethod
import io.spechub.spec.model.Info
import io.spechub.spec.model.MediaType
import io.spechub.spec.model.OpenApiDoc
import io.spechub.spec.model.Operation
import io.spechub.spec.model.Parameter
import io.spechub.spec.model.PathItem
import io.spechub.spec.model.RequestBody
import io.spechub.spec.model.Response
import io.spechub.spec.model.Schema
import io.spechub.spec.model.Server
import io.swagger.v3.oas.models.media.Schema as SwSchema
import java.math.BigDecimal

/** 首字母大写（用于错误消息拼出 JSON 字段名 exclusiveMinimum 等）。 */
private fun String.firstCharUpper(): String = if (isEmpty()) this else replaceFirstChar { it.uppercaseChar() }

/**
 * 将 swagger-parser 转换出的 OAS3 模型映射为内部 spec-model（3.1 语义）。
 * 关键差异处理（§6.5 升级变更日志）：
 *  - 3.0 `nullable: true` → type 列表加入 "null"
 *  - 3.0 `exclusiveMinimum/Maximum: true`（布尔）+ min/max → 3.1 数值语义，
 *    边界值并入 exclusive* 字段，原 min/max 置空
 * swagger-parser 已自动完成 2.0→3 的结构转换（definitions→components 等）。
 */
object OpenApiConverter {

    /** 转换后留痕列表直到归位才暴露；内部富集的辅助返回（doc, notes）。 */
    fun convert(api: io.swagger.v3.oas.models.OpenAPI): Pair<OpenApiDoc, List<ConversionNote>> {
        val notes = mutableListOf<ConversionNote>()
        val info = api.info

        val servers = api.servers?.map { Server(url = it.url ?: "", description = it.description) } ?: emptyList()

        val paths = buildMap {
            api.paths?.forEach { (path, pi) ->
                if (pi == null) return@forEach
                val ops = pi.readOperationsMap()?.entries?.associate { (m, op) ->
                    HttpMethod.valueOf(m.name) to convertOperation(op, path, notes)
                } ?: emptyMap()
                put(path, PathItem(summary = pi.summary, description = pi.description, operations = ops))
            }
        }

        val comp = api.components
        val components = Components(
            schemas = comp?.schemas?.mapValues { (k, v) -> convertSchema(v, "/components/schemas/$k", notes) } ?: emptyMap(),
            responses = comp?.responses?.mapValues { (k, v) -> convertResponse(v, "/components/responses/$k", notes) } ?: emptyMap(),
            parameters = comp?.parameters?.mapValues { (_, v) -> convertParameter(v, notes) } ?: emptyMap(),
            requestBodies = comp?.requestBodies?.mapValues { (_, v) -> convertRequestBody(v, notes) } ?: emptyMap(),
            examples = comp?.examples?.mapValues { (_, v) ->
                Example(summary = v?.summary, description = v?.description, value = v?.value)
            } ?: emptyMap(),
        )

        val doc = OpenApiDoc(
            openapi = "3.1.1",
            info = Info(title = info?.title ?: "", version = info?.version ?: "", description = info?.description),
            servers = servers,
            paths = paths,
            components = components,
        )
        return doc to notes
    }

    private fun convertOperation(
        op: io.swagger.v3.oas.models.Operation,
        path: String,
        notes: MutableList<ConversionNote>,
    ): Operation = Operation(
        operationId = op.operationId,
        summary = op.summary,
        description = op.description,
        tags = op.tags ?: emptyList(),
        parameters = op.parameters?.map { convertParameter(it, notes) } ?: emptyList(),
        requestBody = op.requestBody?.let { convertRequestBody(it, notes) },
        responses = op.responses?.entries?.associate { (code, resp) -> code to convertResponse(resp, "$path", notes) } ?: emptyMap(),
        deprecated = op.deprecated ?: false,
    )

    private fun convertParameter(p: io.swagger.v3.oas.models.parameters.Parameter, notes: MutableList<ConversionNote>): Parameter =
        Parameter(
            name = p.name ?: "",
            location = p.`in` ?: "query",
            required = p.required ?: false,
            description = p.description,
            schema = p.schema?.let { convertSchema(it, "/parameters", notes) },
        )

    private fun convertRequestBody(
        rb: io.swagger.v3.oas.models.parameters.RequestBody,
        notes: MutableList<ConversionNote>,
    ): RequestBody = RequestBody(
        description = rb.description,
        content = convertContent(rb.content, notes),
        required = rb.required ?: false,
    )

    private fun convertContent(
        content: io.swagger.v3.oas.models.media.Content?,
        notes: MutableList<ConversionNote>,
    ): Map<String, MediaType> = content?.entries?.associate { (media, mt) ->
        media to MediaType(
            schema = mt.schema?.let { convertSchema(it, "/content/$media", notes) },
            example = mt.example,
            examples = mt.examples?.mapValues { (_, e) ->
                Example(summary = e.summary, description = e.description, value = e.value)
            } ?: emptyMap(),
        )
    } ?: emptyMap()

    private fun convertResponse(
        r: io.swagger.v3.oas.models.responses.ApiResponse,
        _pointer: String,
        notes: MutableList<ConversionNote>,
    ): Response = Response(
        description = r.description ?: "",
        content = convertContent(r.content, notes),
    )

    private fun convertSchema(sw: SwSchema<*>, pointer: String, notes: MutableList<ConversionNote>): Schema {
        // 3.0 nullable → 3.1 type 数组（升级留痕）
        val typeElements = mutableListOf<String>()
        sw.type?.let { typeElements += it }
        if (sw.nullable == true) {
            typeElements += Schema.TYPE_NULL
            notes += ConversionNote("$pointer: nullable=true → 转换为 type 含 'null'（3.0→3.1）")
        }

        // 3.0 exclusive* 布尔 → 3.1 数值（swagger-models 2.x 为 Boolean；高版本为 Number，双兼容）
        val exclusiveMin = convertExclusiveBound(sw.exclusiveMinimum, sw.minimum, pointer, "minimum", notes)
        val exclusiveMax = convertExclusiveBound(sw.exclusiveMaximum, sw.maximum, pointer, "maximum", notes)

        return Schema(
            type = if (typeElements.isEmpty()) null else typeElements,
            title = sw.title,
            description = sw.description,
            format = sw.format,
            ref = sw.`$ref`,
            properties = sw.properties?.entries?.associate { (k, v) -> k to convertSchema(v, "$pointer/properties/$k", notes) }
                ?: emptyMap(),
            items = sw.items?.let { convertSchema(it, "$pointer/items", notes) },
            required = sw.required ?: emptyList(),
            enumValues = sw.enum ?: emptyList(),
            minLength = sw.minLength?.toLong(),
            maxLength = sw.maxLength?.toLong(),
            minimum = if (exclusiveMin == null) sw.minimum else null,
            maximum = if (exclusiveMax == null) sw.maximum else null,
            exclusiveMinimum = exclusiveMin,
            exclusiveMaximum = exclusiveMax,
            example = sw.example,
            default = sw.default,
            additionalProperties = convertAdditionalProperties(sw.additionalProperties, pointer, notes),
            allOf = sw.allOf?.map { convertSchema(it, "$pointer/allOf", notes) } ?: emptyList(),
            oneOf = sw.oneOf?.map { convertSchema(it, "$pointer/oneOf", notes) } ?: emptyList(),
            anyOf = sw.anyOf?.map { convertSchema(it, "$pointer/anyOf", notes) } ?: emptyList(),
        )
    }

    private fun convertExclusiveBound(
        flag: Any?,
        bound: BigDecimal?,
        pointer: String,
        name: String,
        notes: MutableList<ConversionNote>,
    ): BigDecimal? = when (flag) {
        is Boolean -> if (flag) {
            notes += ConversionNote("$pointer: exclusive${name.firstCharUpper()}=true（3.0 布尔）→ 边界并入 exclusive${name.firstCharUpper()}（3.1 数值）")
            bound
        } else {
            null
        }
        is Number -> flag.toString().toBigDecimal()
        else -> null
    }

    private fun convertAdditionalProperties(
        ap: Any?,
        pointer: String,
        notes: MutableList<ConversionNote>,
    ): Any? = when (ap) {
        is SwSchema<*> -> convertSchema(ap, "$pointer/additionalProperties", notes)
        is Boolean -> ap
        is Map<*, *> -> ap.filterValues { it is SwSchema<*> }
            .mapValues { (k, v) -> (k as? String) to convertSchema(v as SwSchema<*>, "$pointer/additionalProperties", notes) }
            .mapNotNull { it.key?.let { k -> k to it.value!! } }
            .associate { it.first to it.second }
        else -> null
    }
}