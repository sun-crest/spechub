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

/** 评论（I5：挂接口 / 文档 / 仓库下的轻评论；author 为 app_user.username）
 *  稳定寻址＝repo_id + method + path（api 是可重建投影，评论不随其 id 级联）。 */
data class CommentRecord(
    val id: Long? = null,
    val repoId: Long,
    val apiId: Long? = null,
    val docId: Long? = null,
    val method: String = "",
    val path: String = "",
    val author: String,
    val body: String,
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

/** 订阅（I7 §6.3）：消费团队声明对某接口（repo+method+path 稳定寻址）的结构变更关注 */
data class SubscriptionRecord(
    val id: Long? = null,
    val repoId: Long,
    val apiId: Long? = null,
    val method: String,
    val path: String,
    val team: String,
    val notifyUrl: String = "",
    val createdAt: String? = null,
)

/** 变更通知（I7）：摄取检测订阅接口结构变化时落条；team 为消费方标识 */
data class NotificationRecord(
    val id: Long? = null,
    val team: String,
    val repoId: Long,
    val method: String,
    val path: String,
    val summary: String,
    val createdAt: String? = null,
)

/** 评审审批流（I8 §6.4 决策 3「写操作皆审」）：spec 写回待批准，approve 才落真源 */
data class ReviewRecord(
    val id: Long? = null,
    val repoId: Long,
    val path: String,
    val newContent: String,
    val diff: String = "",
    val oldHash: String? = null,
    val status: String = ReviewStatus.PENDING,
    val requester: String,
    val reviewer: String? = null,
    val rejectReason: String? = null,
    val createdAt: String? = null,
    val resolvedAt: String? = null,
)

/** 评审状态常量 */
object ReviewStatus {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    val ALL = setOf(PENDING, APPROVED, REJECTED)
}

/** Doc 域（K AI 文档层，§5.7 独立管理域）：AI 分析的 Markdown，与 Api 域分开管理 */
data class DocRecord(
    val id: Long? = null,
    val repoId: Long,
    val versionId: Long? = null,
    val path: String,
    val title: String,
    val markdown: String,
    /** front-matter 关联接口列表（K3 Doc↔Api 链接） */
    val relatedApis: List<String> = emptyList(),
    val createdAt: String? = null,
)

/** 项目成员角色（I4 RBAC，§6.3 Project.members 落地；等级见 [Role.rank]） */
object Role {
    const val OWNER = "owner"
    const val ADMIN = "admin"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"

    /** viewer 最低、owner 最高；判定「至少有 X 级别」用 rank(role) >= rank(min) */
    fun rank(role: String?): Int = when (role) {
        OWNER -> 3
        ADMIN -> 2
        EDITOR -> 1
        VIEWER -> 0
        else -> -1
    }

    val ALL: Set<String> = setOf(OWNER, ADMIN, EDITOR, VIEWER)
}

/** 平台用户（I4：认证对象；口令为平台发放 token，库中只存 hash） */
data class UserRecord(
    val id: Long? = null,
    val username: String,
    val displayName: String = "",
    val tokenHash: String,
    /** 平台级管理员（由 spechub.admin-token bootstrap）：对任意项目视为 owner */
    val isPlatformAdmin: Boolean = false,
    val createdAt: String? = null,
)

/** 项目成员关系：user → project 的角色（owner/admin/editor/viewer） */
data class MembershipRecord(
    val id: Long? = null,
    val userId: Long,
    val projectId: Long,
    val role: String,
)

/** 用户列表视图（含角色归属，供成员管理 UI） */
data class UserWithRoles(
    val user: UserRecord,
    /** projectId → role 映射；平台 admin 额外加 * 标记 */
    val roles: Map<Long, String>,
)

/** 摄取状态（repo.sync_status） */
object SyncStatus {
    const val NEW = "new"
    const val SYNCING = "syncing"
    const val OK = "ok"
    const val ERROR = "error"
}