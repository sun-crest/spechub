package io.spechub.server.ingest

import io.spechub.server.config.SpecHubProperties
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.index.ApiRecord
import io.spechub.spec.index.ProjectionStore
import io.spechub.spec.index.RepoRecord
import io.spechub.spec.index.SchemaRecord
import io.spechub.spec.index.SyncStatus
import io.spechub.spec.model.io.SpecHash
import io.spechub.spec.parser.ParseResult
import io.spechub.spec.parser.SpecParser
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
    private val projection: ProjectionStore,
    private val hooks: IngestHooks,
    private val props: SpecHubProperties,
    /**
     * 真源解析端口：返回当前 `active` binding 的 URL/分支（retired 绝不可读）。
     * 默认按 [RepoRecord] 自身取值，内核单测/手动装配无需管理库。
     */
    private val sourceResolver: (RepoRecord) -> RepoSource? = { RepoSource(it.url, it.branch) },
) {
    data class RepoSource(
        val url: String,
        val branch: String,
    )

    data class IngestResult(
        val repoId: Long,
        val apiCount: Int,
        val schemaCount: Int,
        val versionCount: Int,
        val docCount: Int = 0,
        val messages: List<String> = emptyList(),
    )

    fun ingest(
        repo: RepoRecord,
        urlOverride: String? = null,
    ): IngestResult {
        // 只认 active binding：无 active 时拒绝摄取，绝不回退到 retired/旧 URL
        val source = sourceResolver(repo) ?: throw IllegalStateException("repo ${repo.id} 没有 active binding，拒绝摄取")
        // 目录名用逻辑仓库 URL 保持镜像稳定（flip 后复用同一 mirror，按新真源 filter 更新）
        val repoDir = gitSourceFor(repo.url)
        // 目录名用干净 URL（含 token 的 override 只喂给 git 命令参数，不落盘）
        val effectiveUrl = urlOverride ?: source.url
        hooks.updateSyncStatus(repo.id, SyncStatus.SYNCING, null)
        val messages = mutableListOf<String>()
        return try {
            git.ensureMirror(effectiveUrl, repoDir)
            val branch = source.branch.ifEmpty { props.defaultBranch }

            val apis = LinkedHashMap<String, ApiRecord>()
            val schemas = LinkedHashMap<String, SchemaRecord>()
            var versionCount = 0

            // 逐文件 upsertVersion/latestSpecHash：N = spec 文件数，耗时以 git 读取为主，
            // 且每文件 hash 判定相互独立（单文件失败可跳过继续），故不合并为批量写（编码规范 §5.1 例外）。
            for (file in findSpecFiles(repoDir, branch)) {
                val content = git.readFile(repoDir, branch, file)
                if (!content.success) {
                    messages += "读取失败 $file：${content.stderr.take(120)}"
                    continue
                }
                when (val parsed = SpecParser.parse(content.output)) {
                    is ParseResult.Success -> {
                        val hash = SpecHash.of(parsed.doc)
                        // I7：与上次摄入的 spec_hash 比对判定「结构变化」（幂等续跑同内容不算）
                        val prevHash = projection.latestSpecHash(repo.id, file)
                        val version = projection.upsertVersion(repo.id, file, hash)
                        versionCount++
                        val fileApis = IndexMapper.extractApis(repo.id, version.id, parsed.doc)
                        // 多 spec 文件可能定义同 method+path（J 导入等）：先到保留（本次摄取内去重，匹配 UNIQUE 约束）
                        fileApis.forEach { apis.putIfAbsent("${it.method} ${it.path}", it) }
                        IndexMapper
                            .extractSchemas(repo.id, version.id, parsed.doc)
                            .forEach { schemas.putIfAbsent(it.name, it) }
                        if (parsed.issues.isNotEmpty()) messages += "$file：警告 ${parsed.issues.joinToString("; "){it.message}}"
                        if (prevHash != hash) {
                            for (a in fileApis) notifySubscribers(repo.id, a.method, a.path, hash)
                        }
                    }

                    is ParseResult.Failure -> {
                        messages += "$file：跳过（${parsed.issues.firstOrNull()?.message ?: "未知错误"}）"
                    }
                }
            }

            // K（Doc 域）：摄取 docs/ 下 Markdown 到独立 doc 投影（两域隔离 §5.7）
            val docs = mutableListOf<io.spechub.spec.index.DocRecord>()
            for (file in findDocFiles(repoDir, branch)) {
                val content = git.readFile(repoDir, branch, file)
                if (!content.success) {
                    messages += "读取失败 $file：${content.stderr.take(120)}"
                    continue
                }
                val parsed = parseDoc(file, content.output)
                if (parsed != null) docs += parsed
            }
            // 投影复合写：apis/schemas/docs 单事务，避免中间态被读到
            projection.replaceProjectionForRepo(repo.id, apis.values.toList(), schemas.values.toList(), docs)

            val result = IngestResult(repo.id, apis.size, schemas.size, versionCount, docs.size, messages)
            hooks.replayReleases(repo.id, projection)
            hooks.updateSyncStatus(repo.id, SyncStatus.OK, null)
            result
        } catch (e: Exception) {
            hooks.updateSyncStatus(repo.id, SyncStatus.ERROR, e.message?.take(500))
            throw e
        }
    }

    /** 发现 spec 文件：优先 §6.2 `specs/` 目录规范，其次宽松探测（解析失败会被隔离记录） */
    private fun findSpecFiles(
        repoDir: Path,
        branch: String,
    ): List<String> {
        val all = git.listFiles(repoDir, branch)
        val specExt = listOf(".yaml", ".yml", ".json")
        val inSpecs = all.filter { it.startsWith("specs/") && specExt.any { it.endsWith(it) } }
        return inSpecs
            .ifEmpty {
                all.filter { f ->
                    specExt.any { f.endsWith(it) } &&
                        (f.contains("openapi") || f.contains("swagger") || f.contains("/api/"))
                }
            }.sorted()
    }

    /** 发现 Doc 域文件：§6.2 `docs/<service>/` 下 Markdown（两域隔离，§5.7） */
    private fun findDocFiles(
        repoDir: Path,
        branch: String,
    ): List<String> {
        val mdExt = listOf(".md", ".markdown")
        return git.listFiles(repoDir, branch).filter { it.startsWith("docs/") && mdExt.any { it.endsWith(it) } }.sorted()
    }

    /** Markdown → DocRecord：front-matter（title/related）解析 + 标题兜底；related 追加「关联接口」节（K3 链接） */
    internal fun parseDoc(
        path: String,
        raw: String,
    ): io.spechub.spec.index.DocRecord? {
        var body = raw
        var title: String? = null
        val related = mutableListOf<String>()

        val fm = Regex("^---\\s*\n(.*?)\n---\\s*\n?", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (fm != null) {
            body = raw.removeRange(fm.range)
            for (line in fm.groupValues[1].lineSequence()) {
                when {
                    line.startsWith("title:") -> {
                        title = line.removePrefix("title:").trim().trim('"', '\'', ' ')
                    }

                    line.startsWith("related:") -> {
                        val listText = line.removePrefix("related:").trim()
                        // `[/pets, /pets/{id}]` 或 `- /pets`（多行）简化：取方括号列表
                        Regex("[^,\\[\\]\\s]+").findAll(listText).forEach { m -> related += m.value.removePrefix("/") }
                    }
                }
            }
        }

        if (title == null) {
            title =
                Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
        }
        if (title == null) title = path.substringAfterLast("/").removeSuffix(".md").removeSuffix(".markdown")

        // related → 追加「关联接口」节（前端渲染为可点击链接跳接口详情）
        if (related.isNotEmpty()) {
            val section = "\n## 关联接口\n\n" + related.distinct().joinToString("\n") { "- [`/$it`](#/api/$it)" } + "\n"
            body = body.trimEnd('\n') + "\n" + section
        }
        return io.spechub.spec.index
            .DocRecord(repoId = 0, path = path, title = title.trim(), markdown = body, relatedApis = related)
    }

    /**
     * I7：某接口结构变化 → 对其每个订阅方落一条通知（消费团队可查/后续挂 webhook）。
     * 逐团队独立写：每条是独立审计事件、不在事务内，单条失败不应影响其它团队（编码规范 §5.1 例外）。
     */
    private fun notifySubscribers(
        repoId: Long,
        method: String,
        path: String,
        hash: String,
    ) {
        for (s in hooks.subscriptionsForEndpoint(repoId, method, path)) {
            hooks.addNotification(s.team, repoId, method, path, "结构变更：$method $path → specHash ${hash.take(10)}…")
        }
    }

    /** url → 稳定可读镜像目录 `<gitSourceDir>/repos/<org>_<repo>`（§7.3） */
    internal fun gitSourceFor(url: String): Path {
        val orgRepo =
            url
                .removeSuffix(".git")
                .substringAfter("://")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return props
            .gitSourcePath()
            .toAbsolutePath()
            .resolve("repos")
            .resolve(orgRepo)
    }
}
