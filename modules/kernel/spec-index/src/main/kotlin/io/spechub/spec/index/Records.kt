package io.spechub.spec.index

/** 行模型（数据实体真源见 §6.3）；时间均为 ISO-8601 文本 */
data class ProjectRecord(
    val id: Long,
    val name: String,
)

data class RepoRecord(
    val id: Long,
    val projectId: Long,
    val url: String,
    val branch: String,
    val syncStatus: String,
    val lastError: String? = null,
)

data class VersionRecord(
    val id: Long,
    val repoId: Long,
    val path: String,
    val specHash: String,
    val stage: String,
)

data class ApiRecord(
    val id: Long? = null,
    val repoId: Long,
    val versionId: Long? = null,
    val method: String,
    val path: String,
    val operationId: String? = null,
    val tag: String? = null,
    val summary: String? = null,
    val specJson: String,
    /** 来源 spec 文件路径（`version.path`，H4「编辑此文件」直达用；列表查询不填充） */
    val specPath: String? = null,
    /** 投影档位（I6）：draft=最新摄取、release=发布快照副本（§6.4 决策 5 双层版本） */
    val stage: String = Stage.DRAFT,
)

data class SchemaRecord(
    val id: Long? = null,
    val repoId: Long,
    val versionId: Long? = null,
    val name: String,
    val specJson: String,
    val stage: String = Stage.DRAFT,
)

/** Mock 规则（§6.3 MockRule，I2）：按字段路径生成 mock 值 */
data class MockRuleRecord(
    val id: Long? = null,
    val repoId: Long,
    /** 为空 = 项目级规则（全仓生效） */
    val apiId: Long? = null,
    /** 字段路径匹配：`data.items.*.name`；也作为 pattern 类型的正则源 */
    val fieldPattern: String,
    /** value / pattern / script（§6.3 三档） */
    val ruleType: String,
    /** value→固定值文本；pattern→内置格式关键字；script→DSL 表达式 */
    val ruleValue: String = "",
)

/** 变更记录（§6.3 ChangeLog，I5 铺底）：写操作留痕，审计 / 订阅通知依据 */
data class ChangelogRecord(
    val id: Long? = null,
    val repoId: Long? = null,
    val apiId: Long? = null,
    val actor: String,
    val action: String,
    val detail: String,
    val createdAt: String? = null,
)

/** 发布档位常量 */
object Stage {
    const val DRAFT = "draft"
    const val RELEASE = "release"
}

/** 变更动作常量（对应 api 层 action 语义，订阅通知据此分组） */
object ChangelogAction {
    const val SPEC_WRITE = "spec.write"
    const val PROMOTE = "promote"
    const val MOCK_RULE = "mock.rule"
}

/** 摄取状态（repo.sync_status） */
object SyncStatus {
    const val NEW = "new"
    const val SYNCING = "syncing"
    const val OK = "ok"
    const val ERROR = "error"
}