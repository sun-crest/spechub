package io.spechub.spec.gstore

import java.io.BufferedReader
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/*
 * D1 —— 系统 git CLI 安全子进程封装（§7 选型表：git 操作统一走系统 git CLI 子进程）。
 *
 * 安全要点：
 * - **参数化**：命令参数一律以 List 传给 ProcessBuilder，绝不 shell 拼接（防注入 / 转义问题）；
 *   通配 `--` 分隔语义由调用方显式给出。
 * - **禁交互**：`GIT_TERMINAL_PROMPT=0`，避免卡死在凭据/提示上。
 * - **超时**：waitFor 限时，超时 destroyForcibly；stdout/stderr 双流并发读取防死锁。
 * - **稳定输出**：`--no-pager`（防分页挂起）+ `-c core.quotepath=false`（中文路径不转义）。
 */

data class GitResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val success: Boolean get() = exitCode == 0

    /** 失败即抛（调用方已明确接受该命令不可失败时使用） */
    fun require(what: String): GitResult {
        require(success) { "$what 失败（exit=$exitCode）\nstderr: $stderr" }
        return this
    }

    val output: String get() = stdout.trim()
}

class GitCli(
    private val gitPath: String = "git",
    private val timeoutSeconds: Long = 120,
) {
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    /**
     * 执行 git 子进程。
     * @param workingDir 工作目录（null = 继承当前进程 CWD；git 会自动感知 -C/--git-dir 参数）
     * @param args 子命令及其参数（不含 `git` 前缀）
     * @param env 追加的环境变量
     */
    fun run(workingDir: Path?, args: List<String>, env: Map<String, String> = emptyMap()): GitResult {
        val command = buildList {
            add(gitPath)
            add("-c"); add("core.quotepath=false")
            add("--no-pager")
            addAll(args)
        }
        val pb = ProcessBuilder(command)
        if (workingDir != null) pb.directory(workingDir.toFile())
        pb.environment().apply {
            put("GIT_TERMINAL_PROMPT", "0")
            env.forEach { (k, v) -> put(k, v) }
        }

        val process = try {
            pb.start()
        } catch (e: java.io.IOException) {
            return GitResult(127, "", "无法启动 git 子进程（$gitPath）：${e.message}")
        }

        val outFut: Future<String> = pool.submit(Callable { readAll(process.inputStream) })
        val errFut: Future<String> = pool.submit(Callable { readAll(process.errorStream) })

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val err = runCatching { errFut.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            return GitResult(-1, "", err.trim() + "\n[git-store] 执行超时（${timeoutSeconds}s），已强制终止：${args.firstOrNull()}")
        }

        val out = runCatching { outFut.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val err = runCatching { errFut.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        return GitResult(process.exitValue(), out, err)
    }

    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
}