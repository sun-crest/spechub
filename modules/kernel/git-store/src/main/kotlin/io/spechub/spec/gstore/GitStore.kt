package io.spechub.spec.gstore

import java.nio.file.Files
import java.nio.file.Path

/**
 * 镜像仓读写（D2-D4）。gitsource 根下的工作副本仓：远程仓 → 平台镜像，写回远程（push）。
 * 所有文件访问限定在镜像仓目录内；文件路径经相对化 + 存在性校验，防路径逃逸（写入单口纪律的配套）。
 */
class GitStore(
    val gitsource: Path,
    private val cli: GitCli = GitCli(),
) {
    init {
        require(Files.isDirectory(gitsource)) { "gitsource 根必须存在: $gitsource" }
    }

    fun repoDir(ref: GitRepoRef): Path = gitsource.resolve(ref.relativePath())

    /** D2 克隆或更新镜像仓。不拉取远程其它分支，跟踪默认分支即可。 */
    fun syncRemote(ref: GitRepoRef, remoteUrl: String): Path {
        val dir = repoDir(ref)
        if (Files.isDirectory(dir.resolve(".git"))) {
            cli.check(dir, listOf("fetch", "origin"))
        } else {
            Files.createDirectories(dir.parent)
            cli.check(gitsource, listOf("clone", remoteUrl, dir.toString()))
        }
        return dir
    }

    /** 镜像仓是否已存在（供摄取器判断首次摄取 or 增量）。 */
    fun hasRepo(ref: GitRepoRef): Boolean = Files.isDirectory(repoDir(ref).resolve(".git"))

    /**
     * D4 提取相对 git 根的变更文件列表（供 ChangeLog / 摄取器消费）。
     * range 缺省时 = 与 HEAD 相比的工作区变更；显式 range（如 "HEAD~1..HEAD"）为提交区间。
     */
    fun changedFiles(ref: GitRepoRef, range: String? = null): List<String> {
        if (!hasRepo(ref)) return emptyList()
        val args = buildList {
            add("diff")
            add("--name-only")
            if (range != null) add(range) else addAll(listOf("HEAD"))
        }
        val out = cli.check(repoDir(ref), args)
        return out.lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** D4 带回显文件的完整 diff 文本（zlib 层文本 diff），供 ChangeLog/预览。 */
    fun diffText(ref: GitRepoRef, range: String): String =
        cli.run(repoDir(ref), listOf("diff", range)).stdout

    /** D3 提交文件 -> 返回新 HEAD SHA（洁净 = 无变更返回 null）。 */
    fun commit(ref: GitRepoRef, files: List<String>, message: String): String? {
        val dir = repoDir(ref)
        val normalized = files.map { normalizeInRepo(dir, it) }
        cli.check(dir, listOf("add", "--", *normalized.map { it.toString() }.toTypedArray()))
        val status = cli.check(dir, listOf("status", "--porcelain"))
        if (status.isBlank()) return null // 无净变更
        cli.check(dir, listOf("-c", "user.name=spec-hub", "-c", "user.email=spec-hub@spechub.local", "commit", "-m", message))
        return cli.check(dir, listOf("rev-parse", "HEAD"))
    }

    /** D3 推送当前分支到远程（镜像仓写回真源）。 */
    fun push(ref: GitRepoRef): String = cli.check(repoDir(ref), listOf("push", "origin", "HEAD"))

    /** 读取镜像仓内文件文本（工作区当前状态）。 */
    fun readText(ref: GitRepoRef, relPath: String): String {
        val p = normalizeInRepo(repoDir(ref), relPath)
        return Files.readString(p)
    }

    /**
     * D4 按指定 git 对象（rev）读取文件文本 —— 消费端正交于工作区状态，
     * fetch 之后即便工作区未更新也能读到远端最新（`git show rev:path`）。
     * rev 缺省 = origin 的默认分支所指向：`origin/HEAD`（fetch 更新的目标）。
     */
    fun readTextAt(ref: GitRepoRef, relPath: String, rev: String = "origin/HEAD"): String? {
        val dir = repoDir(ref)
        if (!hasRepo(ref)) return null
        val r = cli.run(dir, listOf("show", "$rev:$relPath"))
        return if (r.ok) r.stdout else null
    }

    /** 镜像仓默认分支的当前远端 SHA（`origin/<branch>` 解析，用于增量断点）。 */
    fun headSha(ref: GitRepoRef): String? {
        if (!hasRepo(ref)) return null
        val r = cli.run(repoDir(ref), listOf("rev-parse", "--verify", "--quiet", "refs/remotes/origin/HEAD"))
        return if (r.ok) r.stdout.trim() else null
    }

    /** 写文件到镜像仓工作区（尚未 commit；配合 [commit] 落库）。 */
    fun writeText(ref: GitRepoRef, relPath: String, content: String) {
        val p = normalizeInRepo(repoDir(ref), relPath)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    private fun normalizeInRepo(dir: Path, relPath: String): Path {
        val candidate = dir.resolve(relPath).normalize()
        if (!candidate.startsWith(dir)) {
            throw IllegalArgumentException("文件路径逃逸镜像仓目录: $relPath")
        }
        return candidate
    }
}