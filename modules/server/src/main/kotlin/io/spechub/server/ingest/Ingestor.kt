package io.spechub.server.ingest

import io.spechub.spec.gstore.GitStore
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SchemaRecord
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.index.SyncStatus
import io.spechub.spec.model.io.SpecHash
import io.spechub.spec.parser.ParseResult
import io.spechub.spec.parser.SpecParser
import io.spechub.server.config.SpecHubProperties
import java.nio.file.Path

/*
 * E3 —— 摄取器主线：`git-store → spec-parser → 索引`（增量 + 幂等续跑）。
 *
 * - ensureMirror 幂等：已镜像则增量 fetch（D2），仓库 HEAD 未变则拿到同样的文件内容 → specHash 幂等。
 * - 索引是 repo 级可重建投影：先清该仓 api/schema，再整批插入（§6.4 决策 7）。
 * - 背锅隔离（C4）：单个文档解析失败只记 message，不拖垮整个仓；git/IO 异常才整体报错。
 * - spec 文件发现：优先 §6.2 的 `specs/` 目录；无则宽松探测（openapi/swagger/api 命名 + 扩展名）。
 */

class Ingestor(
    private val git: GitStore,
    private val index: SpecIndexStore,
    private val props: SpecHubProperties,
) {

    data class IngestResult(
        val repoId: Long,
        val apiCount: Int,
        val schemaCount: Int,
        val versionCount: Int,
        val messages: List<String> = emptyList(),
    )

    fun ingest(repo: RepoRecord, urlOverride: String? = null): IngestResult {
        val repoDir = gitSourceFor(repo.url)
        // 目录名用干净 URL（含 token 的 override 只喂给 git 命令参数，不落盘）
        val effectiveUrl = urlOverride ?: repo.url
        index.updateSyncStatus(repo.id, SyncStatus.SYNCING)
        val messages = mutableListOf<String>()
        return try {
            git.ensureMirror(effectiveUrl, repoDir)
            val branch = repo.branch.ifEmpty { props.defaultBranch }

            val apis = mutableListOf<ApiRecord>()
            val schemas = mutableListOf<SchemaRecord>()
            var versionCount = 0

            for (file in findSpecFiles(repoDir, branch)) {
                val content = git.readFile(repoDir, branch, file)
                if (!content.success) {
                    messages += "读取失败 $file：${content.stderr.take(120)}"
                    continue
                }
                when (val parsed = SpecParser.parse(content.output)) {
                    is ParseResult.Success -> {
                        val hash = SpecHash.of(parsed.doc)
                        val version = index.upsertVersion(repo.id, file, hash)
                        versionCount++
                        apis += IndexMapper.extractApis(repo.id, version.id, parsed.doc)
                        schemas += IndexMapper.extractSchemas(repo.id, version.id, parsed.doc)
                        if (parsed.issues.isNotEmpty()) messages += "$file：警告 ${parsed.issues.joinToString("; "){it.message}}"
                    }
                    is ParseResult.Failure -> {
                        messages += "$file：跳过（${parsed.issues.firstOrNull()?.message ?: "未知错误"}）"
                    }
                }
            }

            index.replaceApisForRepo(repo.id, apis)
            index.replaceSchemasForRepo(repo.id, schemas)
            val result = IngestResult(repo.id, apis.size, schemas.size, versionCount, messages)
            index.updateSyncStatus(repo.id, SyncStatus.OK)
            result
        } catch (e: Exception) {
            index.updateSyncStatus(repo.id, SyncStatus.ERROR, e.message?.take(500))
            throw e
        }
    }

    /** 发现 spec 文件：优先 §6.2 `specs/` 目录规范，其次宽松探测（解析失败会被隔离记录） */
    private fun findSpecFiles(repoDir: Path, branch: String): List<String> {
        val all = git.listFiles(repoDir, branch)
        val specExt = listOf(".yaml", ".yml", ".json")
        val inSpecs = all.filter { it.startsWith("specs/") && specExt.any { it.endsWith(it) } }
        return inSpecs.ifEmpty {
            all.filter { f ->
                specExt.any { f.endsWith(it) } &&
                    (f.contains("openapi") || f.contains("swagger") || f.contains("/api/"))
            }
        }.sorted()
    }

    /** url → 稳定可读镜像目录 `<gitSourceDir>/repos/<org>_<repo>`（§7.3） */
    internal fun gitSourceFor(url: String): Path {
        val orgRepo = url.removeSuffix(".git")
            .substringAfter("://")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return props.gitSourcePath().toAbsolutePath().resolve("repos").resolve(orgRepo)
    }
}