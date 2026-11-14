package io.spechub.spec.parser

import io.spechub.spec.model.OpenApiDoc

/*
 * C4 容错与错误收集：坏 spec 不 throw，隔离成结构化结果；升级变更另见 UpgradeRecord（C3）。
 * 宽进严出（§6.5）：语法错误 / 版本缺失 / 暂不支持（2.0）都收进 ParseIssue，不拖垮整次摄取。
 */

enum class Severity { ERROR, WARNING }

data class ParseIssue(
    val severity: Severity,
    val kind: String,
    val message: String,
    val path: String? = null,
) {
    companion object {
        fun error(kind: String, message: String, path: String? = null) =
            ParseIssue(Severity.ERROR, kind, message, path)

        fun warning(kind: String, message: String, path: String? = null) =
            ParseIssue(Severity.WARNING, kind, message, path)
    }
}

/** 3.0→3.1 升级变更种类（C3，对接评审流状态——需要人工复核的标记 REVIEW） */
enum class UpgradeKind { NULLABLE_TO_TYPE, EXCLUSIVE_BOUND, TYPE_ARRAY }

/** 升级动作的复核标记：自动即予采纳 / 需人工复核（约 10% 场景，§6.5） */
enum class ReviewFlag { AUTOMATIC, NEEDS_REVIEW }

data class UpgradeRecord(
    val kind: UpgradeKind,
    /** JSON Pointer 风格路径（含 $root 锚），如 `$root/components/schemas/Pet/properties/id` */
    val path: String,
    val summary: String,
    val review: ReviewFlag = ReviewFlag.AUTOMATIC,
)

sealed interface ParseResult {
    val issues: List<ParseIssue>

    /** 解析并（如需要）升级成功，得内部 3.1 模型 */
    data class Success(
        val doc: OpenApiDoc,
        val upgradeLog: List<UpgradeRecord> = emptyList(),
        override val issues: List<ParseIssue> = emptyList(),
    ) : ParseResult

    /** 解析失败：坏文档 / 版本缺失 / 暂不支持（2.0），原因已结构化收集 */
    data class Failure(override val issues: List<ParseIssue>) : ParseResult
}