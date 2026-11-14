package io.spechub.spec.index

/** 行模型（数据实体真源见 §6.3）；时间均为 ISO-8601 文本 */
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
)

data class SchemaRecord(
    val id: Long? = null,
    val repoId: Long,
    val versionId: Long? = null,
    val name: String,
    val specJson: String,
)

/** 摄取状态（repo.sync_status） */
object SyncStatus {
    const val NEW = "new"
    const val SYNCING = "syncing"
    const val OK = "ok"
    const val ERROR = "error"
}