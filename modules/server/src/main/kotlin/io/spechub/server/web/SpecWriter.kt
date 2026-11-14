package io.spechub.server.web

import io.spechub.spec.gstore.GitStore
import io.spechub.spec.gstore.Identity
import io.spechub.spec.index.RepoRecord
import io.spechub.server.config.SpecHubProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/*
 * 写回真源仓（R1-C 兜底形态，§6.2/§6.4 决策 1）：Web 直改 / 平台提交 = 在工作树克隆上改 spec
 * 文件 → 平台身份 commit → push 回真源分支。随后摄取器基于 git 事件/轮询自动重建索引。
 *
 * - 工作树与镜像分开：`<gitSource>/repos-work/<org>_<repo>`（同一 repo 的镜像/工作树并存）。
 * - 写前先 sync 到远端（fetch + reset --hard）防覆盖他人提交（P0 单写者场景足够）。
 * - token 经 UrlTokenStore 提供（走命令参数，不落盘）。
 */

@Component
class SpecWriter(
    private val git: GitStore,
    private val props: SpecHubProperties,
    private val urlTokens: UrlTokenStore,
) {
    /** 平台提交身份（写入 commit 由这台机器统一署名，便于真源审计） */
    private val bot = Identity("spechub-bot", "specbot@spechub.test")

    data class WriteResult(val commit: String, val branch: String)

    /**
     * 覆写/新建 spec 文件并提交推送回真源。
     * @param path 仓内相对路径（如 `specs/pet/openapi.yaml`）
     */
    fun writeSpec(repo: RepoRecord, path: String, content: String): WriteResult {
        val work = workDirFor(repo)
        ensureSyncedWork(repo, work)

        Files.createDirectories(work.resolve(path).parent)
        Files.writeString(work.resolve(path), content)

        git.stage(work, listOf(path)).require("stage $path")
        val commit = git.commit(work, "chore(spechub): update $path", bot).require("commit $path")
        git.push(work, "origin", repo.branch).require("push")
        return WriteResult(git.commitHash(work, "HEAD"), repo.branch)
    }

    /** 该仓全部 spec 文件相对路径（工作树视角，含 specs/ 目录内） */
    fun listSpecs(repo: RepoRecord, branch: String? = null): List<String> {
        val work = workDirFor(repo)
        ensureSyncedWork(repo, work)
        val all = git.listFiles(work, branch ?: repo.branch)
        val ext = listOf(".yaml", ".yml", ".json")
        return all.filter { ext.any { it.endsWith(it) } }.sorted()
    }

    /** 读取单个 spec 文件内容（工作树） */
    fun readSpec(repo: RepoRecord, path: String): String? {
        val work = workDirFor(repo)
        ensureSyncedWork(repo, work)
        val file = work.resolve(path)
        return if (Files.exists(file)) Files.readString(file) else null
    }

    /** 工作树同步到远端最新（增量 clone 或 fetch+reset） */
    private fun ensureSyncedWork(repo: RepoRecord, work: Path) {
        val effectiveUrl = urlTokens.get(repo.id) ?: repo.url
        if (git.isGitRepo(work)) {
            git.syncWorkingToRemote(work, "origin", repo.branch).require("sync $work")
        } else {
            Files.createDirectories(work.parent)
            git.cloneInto(effectiveUrl, work, repo.branch).require("clone into $work")
        }
    }

    private fun workDirFor(repo: RepoRecord): Path {
        val orgRepo = repo.url.removeSuffix(".git")
            .substringAfter("://")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return props.gitSourcePath().toAbsolutePath().resolve("repos-work").resolve(orgRepo)
    }
}