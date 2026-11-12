package io.spechub.spec.parser

import io.spechub.spec.model.OpenApiDoc

/** 一次归一化解析的结果 —— 成功带内部模型与转换留痕，失败带原因（§6.5 转换留痕）。 */
sealed interface ParseResult {
    data class Success(
        val doc: OpenApiDoc,
        val sourceVersion: SpecVersion,
        val notes: List<ConversionNote>,
    ) : ParseResult

    data class Failure(
        val reason: String,
        val sourceVersion: SpecVersion?,
    ) : ParseResult
}

/** 源 spec 的 OpenAPI 方言版本 */
enum class SpecVersion(val label: String) {
    V2("2.0"),
    V300("3.0"),
    V310("3.1"),
    UNKNOWN("?"),
}

/**
 * 转换留痕（升级变更日志项）。
 * needsReview=true 表示转换涉及语义不确定的点，需人工复核（对接"写操作皆审"评审流）。
 */
data class ConversionNote(
    val message: String,
    val needsReview: Boolean = false,
)