package io.spechub.spec.gstore

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D1–D4：系统 git 黑盒验证（镜像/写回/变更提取/参数安全） */
class GitStoreTest {

    @TempDir
    lateinit var temp: Path

    private val cli = GitCli()
    private val store = GitStore()

    private fun identity() = Identity("tester", "t@spechub.test")

    private fun bareRemote(name: String): Path {
        val dir = temp.resolve(name).also { Files.createDirectories(it) }
        cli.run(dir, listOf("init", "--bare", "-b", "main")).require("init bare")
        return dir
    }

    /** 建一个含 `specs/pet.yaml` 初始提交的工作仓（本地 origin 用） */
    private fun seededWorkRepo(name: String): Path {
        val wd = temp.resolve(name).also { Files.createDirectories(it) }
        Files.writeString(wd.resolve("specs").also { Files.createDirectories(it) }.resolve("pet.yaml"), "name: Rex\n")
        cli.run(wd, listOf("init", "-b", "main")).require("init")
        store.stage(wd, listOf(".")).require("stage seed")
        store.commit(wd, "seed", identity()).require("commit seed")
        return wd
    }

    private fun pushSeedTo(work: Path, remote: Path) {
        cli.run(work, listOf("remote", "add", "origin", remote.toString())).require("add remote")
        cli.run(work, listOf("push", "origin", "main")).require("seed push")
    }

    private fun editAndCommit(repo: Path, path: String, content: String, message: String) {
        Files.writeString(repo.resolve(path), content)
        store.stage(repo, listOf(path)).require("stage $path")
        store.commit(repo, message, identity()).require("commit $message")
    }

    // ── D2 · 镜像 clone / fetch + 读文件 ───────────────────────────────────

    @Test
    fun `镜像 clone 读取并增量 fetch 新内容`() {
        val remote = bareRemote("origin")
        val seed = seededWorkRepo("seed").also { pushSeedTo(it, remote) }

        val mirror = temp.resolve("mirror")
        assertTrue(store.ensureMirror(remote.toString(), mirror) is CloneOutcome.Cloned, "首次应为 Cloned")
        assertEquals("name: Rex", store.readFile(mirror, "main", "specs/pet.yaml").output)

        // 上游更新 → 二次 ensureMirror 走增量，能读到最新内容
        editAndCommit(seed, "specs/pet.yaml", "name: Tom\nage: 2\n", "update pet")
        cli.run(seed, listOf("push", "origin", "main")).require("push update")

        assertTrue(store.ensureMirror(remote.toString(), mirror) is CloneOutcome.Updated, "二次应增量更新")
        val latest = store.readFile(mirror, "main", "specs/pet.yaml").require("read latest")
        assertTrue(latest.output.contains("age: 2"), "镜像应同步到最新：${latest.output}")
    }

    @Test
    fun `headRevision 给出镜像仓分支头`() {
        val remote = bareRemote("origin")
        seededWorkRepo("seed2").also { pushSeedTo(it, remote) }
        val mirror = temp.resolve("mirror2")
        store.ensureMirror(remote.toString(), mirror)
        assertTrue(store.headRevision(mirror, "main").output.matches(Regex("[0-9a-f]{40}")))
    }

    // ── D3 · commit / push 写回真源仓 ──────────────────────────────────────

    @Test
    fun `平台身份 commit 并 push 回真源`() {
        val remote = bareRemote("origin")
        seededWorkRepo("seed3").also { pushSeedTo(it, remote) }

        val work = temp.resolve("work")
        store.cloneInto(remote.toString(), work).require("clone")

        Files.writeString(work.resolve("specs/pet.yaml"), "name: Platform Edited\n")
        store.stage(work, listOf("specs/pet.yaml")).require("stage edit")
        store.commit(work, "chore(platform): update pet spec", Identity("specbot", "bot@spechub.test"))
            .require("platform commit")
        store.push(work, "origin", "main").require("platform push")

        // 真源仓提交身份 = specbot（平台身份），内容已同步
        val head = cli.run(remote, listOf("log", "-1", "--format=%an|%ae|%s")).output
        assertEquals("specbot|bot@spechub.test|chore(platform): update pet spec", head)
        val content = cli.run(remote, listOf("show", "main:specs/pet.yaml")).output
        assertEquals("name: Platform Edited", content)
    }

    @Test
    fun `commitAllTracked 自动提交所有已跟踪修改`() {
        val repo = seededWorkRepo("work2")
        // tracked 修改（不手动 stage）+ untracked 新文件
        Files.writeString(repo.resolve("specs/pet.yaml"), "name: Bulk\n")
        Files.writeString(repo.resolve("specs/untracked-too.yaml"), "x: 1\n")
        store.commitAllTracked(repo, "bulk edit", identity()).require("commitAll")

        // -a 只提交已跟踪修改；untracked 应保留在 status
        val leftover = store.status(repo)
        assertTrue(leftover.all { it.isUntracked }, "untracked 应留下：$leftover")
        assertEquals(setOf("specs/untracked-too.yaml"), leftover.map { it.path }.toSet())
        assertTrue(store.log(repo, maxCount = 2).any { it.subject == "bulk edit" }, "bulk edit 应已提交")
    }

    // ── D4 · status / diff / log 变更提取 ──────────────────────────────────

    @Test
    fun `status 区分新增 修改 删除 未跟踪`() {
        val repo = seededWorkRepo("work3")
        editAndCommit(repo, "specs/extra.yaml", "extra\n", "add extra")       // 预备被删文件
        cli.run(repo, listOf("rm", "specs/extra.yaml")).require("rm")          // D（已暂存删除）
        Files.writeString(repo.resolve("specs/pet.yaml"), "name: Modified\n")  // M（未提交修改）
        Files.writeString(repo.resolve("brand-new.yaml"), "new\n")             // ??（未跟踪）

        val files = store.status(repo).associateBy { it.path }
        assertTrue(files.getValue("specs/pet.yaml").isModified, "pet 应为修改：${files.getValue("specs/pet.yaml").code}")
        assertTrue(files.getValue("specs/extra.yaml").isDeleted, "extra 应为删除：${files.getValue("specs/extra.yaml").code}")
        assertTrue(files.getValue("brand-new.yaml").isUntracked, "brand-new 应为未跟踪")
    }

    @Test
    fun `diffStat 与 log 给 ChangeLog 供数`() {
        val repo = seededWorkRepo("work4")
        editAndCommit(repo, "specs/pet.yaml", "name: Rex\nboss: Jane\n", "feat(pet): add boss")

        val stats = store.diffStat(repo, "HEAD~1")
        assertEquals("specs/pet.yaml", stats.single().path)
        assertTrue(stats.single().added >= 1)

        val commits = store.log(repo, maxCount = 5)
        assertEquals(2, commits.size)
        assertEquals("feat(pet): add boss", commits.first().subject)
        assertEquals("t@spechub.test", commits.first().authorEmail)
    }

    // ── 参数安全 · D1 ────────────────────────────────────────────────────────

    @Test
    fun `含空格与特殊字符文件名不被 shell 解释`() {
        val repo = seededWorkRepo("work5")
        val evilPath = "specs/evil - (x) \\\$(rm -rf) --help.yaml"
        Files.writeString(repo.resolve(evilPath), "x: 1\n")

        store.stage(repo, listOf(evilPath)).require("stage special path")
        store.commit(repo, "add evil", identity()).require("commit")

        assertTrue(store.status(repo).isEmpty(), "特殊字符文件应已提交、status clean：${store.status(repo)}")
    }
}