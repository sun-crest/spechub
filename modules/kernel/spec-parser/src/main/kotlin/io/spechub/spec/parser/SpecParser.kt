package io.spechub.spec.parser

import io.spechub.spec.model.io.SpecFormat
import io.spechub.spec.model.io.SpecModelIO
import tools.jackson.databind.JsonNode

/*
 * C1 自研解析器（Jackson 3 / tools.jackson 直读 JSON/YAML）+ 版本探测。
 *
 * 版本探测：`openapi: 3.1.x` / `3.0.x` → 可解析；`swagger: "2.0"` → 结构化升级推迟 P2 J2，
 * 明确报错（§6.5）；缺版本 → 非 OpenAPI 文档。
 * 3.1 直通模型；3.0 经 tree 层语义升级后再入内部 3.1 模型（OpenApiUpgrader，C2）。
 */

enum class DetectedVersion { OPENAPI_31, OPENAPI_30, SWAGGER_20, UNKNOWN, NONE }

object SpecParser {

    fun parse(text: String, format: SpecFormat? = null): ParseResult {
        val fmt = format ?: SpecModelIO.detectFormat(text)
        val tree = try {
            SpecModelIO.parseToTree(text, fmt)
        } catch (e: Exception) {
            return ParseResult.Failure(
                listOf(ParseIssue.error("syntax", "文档无法解析为 JSON/YAML：${e.message?.take(160) ?: e::class.java.simpleName}")),
            )
        }
        return when (detect(tree)) {
            DetectedVersion.OPENAPI_31 -> parseDirect31(tree)
            DetectedVersion.OPENAPI_30 -> OpenApiUpgrader.upgradeAndConvert(tree)
            DetectedVersion.SWAGGER_20 -> ParseResult.Failure(
                listOf(ParseIssue.error(
                    "unsupported-version",
                    "Swagger 2.0 的结构化升级（definitions→components.schemas 等）推迟到 P2 J2（产品方案 §6.5）；" +
                        "请提供 OpenAPI 3.0/3.1 文档，或用工具先升级为 3.0 再入 SpecHub",
                )),
            )
            DetectedVersion.UNKNOWN -> ParseResult.Failure(
                listOf(ParseIssue.error("unknown-version", "存在 openapi 字段但版本号无法识别（仅支持 3.0.x / 3.1.x）")),
            )
            DetectedVersion.NONE -> ParseResult.Failure(
                listOf(ParseIssue.error("not-openapi", "缺少 openapi / swagger 版本字段，不是可识别的 OpenAPI 文档")),
            )
        }
    }

    /** 版本探测（树根查找 openapi / swagger 字段，非对象时安全返回 NONE） */
    fun detect(tree: JsonNode): DetectedVersion {
        val openapi = tree.get("openapi")?.asString()
        return when {
            openapi != null && openapi.startsWith("3.1") -> DetectedVersion.OPENAPI_31
            openapi != null && openapi.startsWith("3.0") -> DetectedVersion.OPENAPI_30
            openapi != null -> DetectedVersion.UNKNOWN
            tree.get("swagger")?.asString()?.startsWith("2.0") == true -> DetectedVersion.SWAGGER_20
            else -> DetectedVersion.NONE
        }
    }

    /** 3.1：直读入内部模型（无升级项） */
    private fun parseDirect31(tree: JsonNode): ParseResult =
        try {
            ParseResult.Success(SpecModelIO.openApiFromTree(tree))
        } catch (e: Exception) {
            ParseResult.Failure(
                listOf(ParseIssue.error("model-conversion", "树→内部模型转换失败：${e.message?.take(160)}")),
            )
        }
}