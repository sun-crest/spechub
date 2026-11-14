package io.spechub.spec.gstore

import java.nio.file.Files
import java.nio.file.Path

/*
 * D2–D4 —— 基于 GitCli 的高层仓库操作。
 *
 * 两种仓库形态（§7.3「镜像 + 物化」）：
 * - **镜像仓**（bare `--mirror`）：聚合层只读侧，镜像远端全部 refs，读取文件用 `show`，永不做本地写。
 * - **工作树仓**（working）：平台直改 / 平台提交写回（R1-C 兜底形态）用；`git reset --hard` 同步远端。
 *
 * 身份注入：commit 一律显式 `-c user.name/-c user.email`（平台提交身份），不依赖全局 git 配置。
 */

data class Identity(val name: String, val email: String)

/** git status --porcelain=v1 的一行：`XY path` 或 `XY old -> new`（R/C） */
data class ChangedFile(
    /** index 区状态位（' ' 表示未暂存 / '?' 未跟踪） */
    val index: Char,
    /** 工作树状态位 */
    val worktree: Char,
    val path: String,
    val oldPath: String? = null,
) {
    val code: String get() = "$index$worktree"
    val isUntracked: Boolean get() = index == '?' && worktree == '?'
    val isStaged: Boolean get() = index != ' ' && index != '?'
    val isDeleted: Boolean get() = worktree == 'D' || index == 'D'
    val isAdded: Boolean get() = worktree == 'A' || index == 'A'
    val isRenamed: Boolean get() = index == 'R' || worktree == 'R'
    val isModified: Boolean get() = worktree == 'M' || index == 'M' || worktree == 'm' || index == 'm'
}

/** git diff --numstat 一行：`added	deleted	path` */
data class DiffStat(
    val path: String,
    val added: Int,
    val deleted: Int,
)

/** git log 一行摘要 */
data class CommitSummary(
    val hash: String,
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val isoTime: String,
)

sealed interface CloneOutcome {
    /** 首次镜像成功 */
    data class Cloned(val target: Path) : CloneOutcome

    /** 已存在且已增量更新 */
    data class Updated(val fetchResult: GitResult) : CloneOutcome
}

class GitStore(private val cli: GitCli = GitCli()) {

    // ── D2 · clone / fetch 镜像远程仓 ─────────────────────────────────────

    /** 镜像 clone（bare）；target 已存在且为 git 仓则跳过（调用方走 [mirrorFetch] 增量） */
    fun mirrorClone(url: String, target: Path): GitResult {
        Files.createDirectories(target.parent)
        return cli.run(target.parent, listOf("clone", "--mirror", "--", url, target.toString()))
    }

    /**
     * 镜像仓增量更新：显式 URL fetch（不依赖 remote 配置），
     * 全 refs 镜像（heads + tags 全量映射，避开注释里的斜星号序列），满足 --mirror 语义。
     * 凭据只出现在命令参数中、永不落盘（见 §0 E2 决策：token 不落库）。
     */
    fun mirrorFetch(target: Path, url: String): GitResult =
        cli.run(null, listOf(
            "--git-dir=${target.toString()}", "fetch", "--prune", url,
            "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*",
        ))

    /** 工作树 clone（写回 / 平台直改用） */
    fun cloneInto(url: String, target: Path, branch: String? = null): GitResult {
        Files.createDirectories(target.parent)
        val args = buildList {
            add("clone")
            if (branch != null) { add("--branch"); add(branch) }
            add("--"); add(url); add(target.toString())
        }
        return cli.run(target.parent, args)
    }

    /**
     * ensureMirror：幂等镜像——已存在则增量 fetch（用传入 url，透明凭据），否则 clone。
     * clone 后把 remote.origin.url 清洗为无凭据 URL（token 不落盘）；fetch 从不用 remote 配置。
     * 是 E3 摄取器幂等续跑的底座。
     */
    fun ensureMirror(url: String, target: Path): CloneOutcome =
        if (isGitRepo(target)) CloneOutcome.Updated(mirrorFetch(target, url))
        else {
            mirrorClone(url, target).require("镜像 clone $url → $target")
            cli.run(null, listOf("--git-dir=${target.toString()}", "remote", "set-url", "origin", stripCredentials(url)))
            CloneOutcome.Cloned(target)
        }

    /** 只读侧工作树同步到远端分支（fetch + reset --hard；镜像场景不如 mirror ＋ [readFile] 干净，见注释） */
    fun syncWorkingToRemote(repo: Path, remote: String = "origin", branch: String? = null): GitResult {
        val fetched = cli.run(repo, listOf("fetch", "--prune", remote))
        if (!fetched.success) return fetched
        val branchName = branch ?: currentBranch(repo)
        return cli.run(repo, listOf("reset", "--hard", "$remote/$branchName"))
    }

    /** 从任意仓读取某修订的单个文件内容（mirror 仓用 `show`，无需工作树） */
    fun readFile(repo: Path, revision: String, path: String): GitResult =
        cli.run(null, listOf("--git-dir=${repo.toString()}", "show", "$revision:$path"))

    /** headRevision：仓 HEAD / 镜像仓分支头的 commit hash（feed 摄取幂等续跑） */
    fun headRevision(repo: Path, rev: String = "HEAD"): GitResult =
        cli.run(repo, listOf("rev-parse", rev))

    /** 列出某修订的全部文件相对路径（镜像仓物化发现 spec 文件用，E3 摄取器） */
    fun listFiles(repo: Path, revision: String = "HEAD"): List<String> {
        val r = cli.run(null, listOf("--git-dir=${repo.toString()}", "ls-tree", "-r", "--name-only", revision))
        if (!r.success) return emptyList()
        return r.stdout.lineSequence().filter { it.isNotBlank() }.toList()
    }

    fun isGitRepo(dir: Path): Boolean = cli.run(dir, listOf("rev-parse", "--git-dir")).success

    private fun currentBranch(repo: Path): String =
        cli.run(repo, listOf("rev-parse", "--abbrev-ref", "HEAD")).output.ifEmpty { "main" }

    /** 剥离 URL 内嵌凭据（如 `https://oauth2:TOKEN@host/path` → `https://host/path`），token 不落盘 */
    private fun stripCredentials(url: String): String =
        if (url.contains("://") && url.contains("@")) {
            "${url.substringBefore("://")}://${url.substringAfter('@')}"
        } else {
            url
        }

    // ── D3 · commit / push 写回真源仓 ───────────────────────────────────────

    /** 把指定路径加入暂存（`--` 防杠示意：路径以字面量传入） */
    fun stage(repo: Path, paths: List<String>): GitResult =
        cli.run(repo, listOf("add", "--") + paths)

    /** 提交当前暂存区 */
    fun commit(repo: Path, message: String, author: Identity): GitResult = doCommit(repo, message, author, all = false)

    /** 提交所有已跟踪文件的修改（Web 直改「改完即提交」用） */
    fun commitAllTracked(repo: Path, message: String, author: Identity): GitResult =
        doCommit(repo, message, author, all = true)

    private fun doCommit(repo: Path, message: String, author: Identity, all: Boolean): GitResult {
        val identity = listOf(
            "-c", "user.name=${author.name}",
            "-c", "user.email=${author.email}",
        )
        val args = buildList {
            addAll(identity)
            add("commit")
            if (all) add("-a") // commit 的子选项；放在 -m 前
            add("-m"); add(message)
        }
        return cli.run(repo, args)
    }

    /** push 到远端分支（平台提交写回真源 / PR 分支） */
    fun push(repo: Path, remote: String = "origin", branch: String): GitResult =
        cli.run(repo, listOf("push", remote, branch))

    /** commit 结果的 hash（提交成功后可用） */
    fun commitHash(repo: Path, rev: String = "HEAD"): String = headRevision(repo, rev).output

    // ── D4 · diff / status 提取变更（喂给 ChangeLog）────────────────────────

    /** porcelain=v1 解析为结构化变更清单（含 rename 旧路径） */
    fun status(repo: Path): List<ChangedFile> {
        val r = cli.run(repo, listOf("status", "--porcelain=v1"))
        if (!r.success) return emptyList()
        return r.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val index = line[0]
                val worktree = if (line.length > 1) line[1] else ' '
                val rest = line.drop(3) // 前 2 状态位 + 1 空格
                if ((index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C') && rest.contains(" -> ")) {
                    val (old, new) = rest.split(" -> ", limit = 2)
                    ChangedFile(index, worktree, new.trim(), old.trim())
                } else {
                    ChangedFile(index, worktree, rest.trim())
                }
            }
            .toList()
    }

    /** 相对 base 的增量统计；`--no-index` 不启用，binary 行 added/deleted 记为 0 */
    fun diffStat(repo: Path, base: String = "HEAD"): List<DiffStat> {
        val r = cli.run(repo, listOf("diff", "--numstat", base))
        if (!r.success) return emptyList()
        return r.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("\t")
                val added = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val deleted = parts.getOrNull(1)?.toIntOrNull() ?: 0
                DiffStat(parts.getOrNull(2) ?: "", added, deleted)
            }
            .toList()
    }

    /** 统一全量 diff 文本（变更日志留存 / 评审展示用） */
    fun diff(repo: Path, base: String = "HEAD"): GitResult =
        cli.run(repo, listOf("diff", base))

    /** 最近提交摘要（ChangeLog 供给端；单位分隔符解析保持字段可靠） */
    fun log(repo: Path, maxCount: Int = 20, branch: String? = null): List<CommitSummary> {
        val fmt = "%H%x1f%an%x1f%ae%x1f%aI%x1f%s"
        val args = buildList {
            add("log")
            if (maxCount > 0) { add("-n"); add(maxCount.toString()) }
            add("--format=$fmt")
            if (branch != null) add(branch)
        }
        val r = cli.run(repo, args)
        if (!r.success) return emptyList()
        return r.stdout.lineSequence()
            .filter { it.contains("\u001f") }
            .map { line ->
                val fields = line.split("\u001f", limit = 5)
                CommitSummary(
                    hash = fields[0],
                    subject = fields.getOrElse(4) { "" },
                    authorName = fields.getOrElse(1) { "" },
                    authorEmail = fields.getOrElse(2) { "" },
                    isoTime = fields.getOrElse(3) { "" },
                )
            }
            .toList()
    }
}