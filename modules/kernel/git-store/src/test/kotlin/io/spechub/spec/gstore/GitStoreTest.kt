package io.spechub.spec.gstore

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * git-store 集成测试：本机 bare 仓模拟远程真源（file:// URL），镜像仓走完整闭环。
 * 依赖系统 git —— 环境无 git 时跳过。
 */
class GitStoreTest {

    @TempDir
    lateinit var temp: Path

    private val cli = GitCli()

    private fun gitAvailable(): Boolean {
        val r = cli.run(temp, listOf("--version"))
        return r.ok && r.stdout.contains("git version")
    }

    /** 在一个独立工作仓 seed 里初始化一个 spec 文件并推到 bare 远程，返回远程路径。 */
    private fun seedRemote(startSha: String): Path {
        val remote = temp.resolve("seed-remote.git")
        val seed = temp.resolve("seed-ws")
        Files.createDirectories(seed)
        seed.resolve("spec/openapi.yaml").apply {
            parent.createDirectories()
            writeText("openapi: 3.0.3\ninfo:\n  title: seed v$startSha\n  version: $startSha\npaths: {}\n")
        }
        // bare 远端用 -b main：真实 Gitea 会把 HEAD 指向默认分支，这里需等价模拟
        cli.check(temp, listOf("init", "--bare", "-b", "main", remote.toString()))
        cli.check(temp, listOf("init", "-b", "main", seed.toString()))
        // 第一次 push 前无历史，`push -u origin HEAD` 即可；push 后确保 bare 的 HEAD 指向 main
        cli.check(temp, listOf("--git-dir=$remote", "symbolic-ref", "HEAD", "refs/heads/main"))
        cli.check(seed, listOf("add", "."))
        cli.check(seed, listOf("-c", "user.name=seed", "-c", "user.email=seed@local", "commit", "-m", "seed v$startSha"))
        cli.check(seed, listOf("remote", "add", "origin", remote.toString()))
        cli.check(seed, listOf("push", "-u", "origin", "HEAD"))
        return remote
    }

    @Test
    fun `clone mirror then fetch incremental change then extract diff`() {
        if (!gitAvailable()) return

        val gits = temp.resolve("gitsource").createDirectories()
        val store = GitStore(gits)
        val ref = GitRepoRef("acme", "billing-api")
        val remote = seedRemote("1.0.0")

        // D2 clone
        store.syncRemote(ref, remote.toUri().toString())
        assertTrue(store.hasRepo(ref), "镜像仓应已克隆")
        assertEquals("repos/acme/billing-api", ref.relativePath().toString())
        assertNotNull(store.readTextAt(ref, "spec/openapi.yaml"), "应能读到远端 spec")
        assertTrue(store.readTextAt(ref, "spec/openapi.yaml")!!.contains("seed v1.0.0"))

        // 远端新增一个 commit（模拟生产端推送 v1.1.0）
        val seed = temp.resolve("seed-ws")
        seed.resolve("spec/openapi.yaml").writeText(
            "openapi: 3.1.0\ninfo:\n  title: seed v1.1.0\n  version: 1.1.0\npaths: {}\n"
        )
        cli.check(seed, listOf("add", "."))
        cli.check(seed, listOf("-c", "user.name=seed", "-c", "user.email=seed@local", "commit", "-m", "bump 1.1.0"))
        cli.check(seed, listOf("push"))
        val sha11 = cli.check(seed, listOf("rev-parse", "HEAD")).trim()

        // D2 update + D4 diff
        store.syncRemote(ref, remote.toUri().toString())
        val headBefore = store.headSha(ref)
        val changed = store.changedFiles(ref, "HEAD..origin/HEAD")
        assertTrue(changed.any { it.endsWith("spec/openapi.yaml") }, "diff 应含 spec 文件: $changed")
        assertEquals("3.1.0", specVersionOf(store.readTextAt(ref, "spec/openapi.yaml")!!))
        assertEquals(sha11, store.headSha(ref) ?: "", "fetch 后 origin/HEAD 应前进到新 SHA")
        assertEquals(sha11, headBefore ?: "", "origin/HEAD 应与新 SHA 一致")
    }

    @Test
    fun `platform edit commits and pushes back to remote`() {
        if (!gitAvailable()) return

        val gits = temp.resolve("gitsource").createDirectories()
        val store = GitStore(gits)
        val ref = GitRepoRef("vendor", "device-api")
        val remote = seedRemote("0.1.0")

        store.syncRemote(ref, remote.toUri().toString())

        // 平台直改（Web 编辑 → 写回真源，R1-C 兜底形态）
        store.writeText(ref, "spec/openapi.yaml",
            "openapi: 3.0.3\ninfo:\n  title: device api edited\n  version: 0.1.1\npaths: {}\n")
        val newSha = store.commit(ref, listOf("spec/openapi.yaml"), "chore: web 平台直改 title")
        assertNotNull(newSha, "应完成一次平台提交")

        store.push(ref)

        // 远程应存在平台提交
        val seed = temp.resolve("seed-ws")
        cli.check(seed, listOf("fetch", "--all"))
        cli.check(seed, listOf("-c", "user.name=seed", "-c", "user.email=seed@local", "merge", "FETCH_HEAD", "--no-edit"))
        assertEquals("device api edited", seed.resolve("spec/openapi.yaml").readText().substringAfter("title: ").substringBefore("\n"))
    }

    @Test
    fun `path escape is rejected`() {
        val gits = temp.resolve("gitsource").createDirectories()
        val store = GitStore(gits)
        val ref = GitRepoRef("a", "b")
        // 提交文件前先有仓库才测试逃逸——直接对 /etc/passwd 路径挑逃逸
        store.syncRemote(ref, seedRemote("0.0.1").toUri().toString())
        try {
            store.commit(ref, listOf("../../../../etc/malware"), "bad")
            throw AssertionError("路径逃逸应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("逃逸"))
        }
    }

    private fun specVersionOf(yaml: String): String =
        Regex("openapi:\\s*[\"']?([0-9.]+)").find(yaml)!!.groupValues[1]
}