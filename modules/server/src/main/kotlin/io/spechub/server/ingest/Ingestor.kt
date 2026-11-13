package io.spechub.server.ingest

import io.spechub.spec.gstore.GitRepoRef
import io.spechub.spec.gstore.GitStore
import io.spechub.spec.index.IndexDb
import io.spechub.spec.index.IndexExtractor
import io.spechub.spec.index.SpecIndexStore
import io.spechub.spec.model.specHash
import io.spechub.spec.parser.ParseResult
import io.spechub.spec.parser.SpecNormalizer
import io.spechub.spec.parser.detectSpecVersion
import io.spechub.server.config.SpecHubProperties
import java.nio.file.Files
import java.util.stream.Stream
import org.springframework.stereotype.Service

/**
 * 摄取器主线（E3）：远程仓 → 镜像（git-store）→ 归一化（spec-parser）→ 索引快照（spec-index）。
 * 幂等续跑：specHash 未变时 spec-index 短路，不重复写 version。
 * P0 边界：每仓取第一个成功归一化且含 paths 的 spec 文件（多 spec 文件属 §6.5 摄入边界，后续展开）。
 */
@Service
class Ingestor(
    private val props: SpecHubProperties,
    private val gitStore: GitStore,
    private val index: SpecIndexStore,
) {

    data class Result(val repoId: Long, val specFile: String?, val unchanged: Boolean)

    /**
     * 摄取一个仓。remoteUrl 已注册或新仓首次同步均可。
     * @param source 触发来源（cli / webhook / poll），落入 version.source
     */
    fun ingest(
        project: String,
        org: String,
        name: String,
        remoteUrl: String,
        branch: String = "main",
        source: String,
        message: String? = null,
        token: String? = null,
    ): Result {
        val ref = GitRepoRef(org, name)
        // E2 凭据注入：token 只拼进本次同步的 http userinfo，registry 落库仍存原始 URL
        val syncUrl = token?.let { injectToken(remoteUrl, it) } ?: remoteUrl
        val dir = gitStore.syncRemote(ref, syncUrl)

        // 找 spec 文件并归一化
        val specFile = findSpecFileIn(dir) ?: return Result(repoId = -1, specFile = null, unchanged = true)

        val raw = gitStore.readText(ref, specFile)
        val doc = when (val parsed = SpecNormalizer.normalize(raw)) {
            is ParseResult.Success -> parsed.doc
            is ParseResult.Failure -> throw SpecFileInvalidException(ref, specFile, parsed.reason)
        }
        if (doc.paths.isEmpty()) throw SpecFileInvalidException(ref, specFile, "归一化后无 paths（非接口文档）")

        val specHash = doc.specHash()
        val apis = IndexExtractor.apis(doc, repoId = 0, specHash = specHash)
        val schemas = IndexExtractor.schemas(doc, repoId = 0, specHash = specHash)

        val result = index.ingestSnapshot(
            project = project, org = org, name = name, remoteUrl = remoteUrl, branch = branch,
            specFile = specFile, specHash = specHash, specVersion = specVersionOf(raw),
            apis = apis, schemas = schemas,
            commitSha = gitStore.headSha(ref), source = source, message = message,
        )
        return Result(result.repoId, specFile, result.unchanged)
    }

    /**
     * 在镜像仓工作区找 spec 文件：扩展名 yaml/yml/json，先 normalize 试错，
     * 成功且含 paths 的第一个文件胜出。跳过 .git。
     */
    private fun specVersionOf(raw: String): String = detectSpecVersion(raw).label

    /** 把 `token` 拼进 http(s) URL 的 userinfo 段；非 http(s) 或已有 userinfo 则原样返回。 */
    internal fun injectToken(url: String, token: String): String {
        val scheme = url.takeWhile { it != ':' }
        if (scheme != "http" && scheme != "https") return url
        val rest = url.removePrefix("$scheme://")
        val (authority, tail) = rest.split('/', limit = 2).let {
            it[0] to if (it.size > 1) "/${it[1]}" else ""
        }
        // authority 已含 userinfo（user:pass@host）时不覆盖，避免破坏已配凭据
        return if (authority.contains('@')) url else "$scheme://$token@$authority$tail"
    }

    private fun findSpecFileIn(dir: java.nio.file.Path): String? {
        val candidates = mutableListOf<String>()
        Files.walk(dir).use { stream: Stream<java.nio.file.Path> ->
            stream.forEach { p ->
                if (Files.isRegularFile(p) && p.fileName.toString().let { f ->
                        f.endsWith(".yaml") || f.endsWith(".yml") || f.endsWith(".json")
                    } && !p.toString().contains("/.git/")
                ) {
                    candidates += dir.relativize(p).toString()
                }
            }
        }
        // 优先级：openapi/swagger 命名靠前，其余按字母序（稳定）
        candidates.sortWith(compareByDescending<String> { it.contains("openapi") || it.contains("swagger") }.thenBy { it })
        for (c in candidates) {
            val raw = try {
                Files.readString(dir.resolve(c))
            } catch (e: Exception) {
                continue
            }
            val parsed = SpecNormalizer.normalize(raw)
            if (parsed is ParseResult.Success && parsed.doc.paths.isNotEmpty()) return c
        }
        return null
    }
}

class SpecFileInvalidException(
    val ref: GitRepoRef,
    val specFile: String?,
    val reason: String,
) : Exception("spec 文件无效 [$ref/$specFile]: $reason")