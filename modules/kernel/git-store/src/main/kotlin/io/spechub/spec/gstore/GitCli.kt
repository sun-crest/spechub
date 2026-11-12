package io.spechub.spec.gstore

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 系统 git CLI 子进程封装（D1）。
 * 安全纪律：
 *  - 参数化调用（ProcessBuilder 命令列表），绝不走 shell、不拼接命令行字符串 —— 路径/消息天然免注入
 *  - `--` 终结选项解析，参数段不受 option 注入污染
 *  - 静默非交互：GIT_TERMINAL_PROMPT=0 防超时挂起，输出带 `--no-pager`
 *  - 统一超时：超时强杀并抛 [GitTimeoutException]，避免子进程泄漏
 */
class GitCli(
    private val gitBinary: String = "git",
    private val defaultTimeoutSec: Long = 120,
) {
    /** 执行 git 命令，返回完整输出捕获。不会抛运行时异常——用 [GitResult.exitCode] 判断。 */
    fun run(workDir: Path, args: List<String>, timeoutSec: Long = defaultTimeoutSec): GitResult {
        val cmd = buildList {
            add(gitBinary)
            add("--no-pager")
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(false)
        pb.environment().putAll(
            mapOf(
                "GIT_TERMINAL_PROMPT" to "0",
                "LC_ALL" to "C", // 输出固定为 C locale，porcelain 解析稳定
                "GIT_OPTIONAL_LOCKS" to "0",
            )
        )
        val proc = pb.start()
        val outFut = CompletableFuture.supplyAsync { proc.inputStream.readAllBytes() }
        val errFut = CompletableFuture.supplyAsync { proc.errorStream.readAllBytes() }
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            proc.waitFor()
            throw GitTimeoutException("git ${args.joinToString(" ")} 超过 ${timeoutSec}s 被终止（cwd=$workDir）")
        }
        return GitResult(
            exitCode = proc.exitValue(),
            stdout = String(outFut.get(), Charsets.UTF_8),
            stderr = String(errFut.get(), Charsets.UTF_8),
        )
    }

    /** 校验结果并返回 stdout（去掉末尾换行）。失败抛 [GitCommandException] 带诊断。 */
    fun check(workDir: Path, args: List<String>, timeoutSec: Long = defaultTimeoutSec): String {
        val r = run(workDir, args, timeoutSec)
        if (r.exitCode != 0) {
            throw GitCommandException("git ${args.joinToString(" ")} 失败（exit=${r.exitCode}）\nstderr: ${r.stderr.trim()}")
        }
        return r.stdout.trimEnd('\n')
    }
}

sealed class GitException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GitCommandException(message: String) : GitException(message)

class GitTimeoutException(message: String) : GitException(message)

data class GitResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/** 源仓引用：org + repo，两层路径（§7.3 gitsource/repos/<org>/<repo>）。 */
data class GitRepoRef(
    val org: String,
    val repo: String,
) {
    init {
        require(SEGMENT.matches(org) && SEGMENT.matches(repo)) {
            "org/repo 段只允许字母数字 / . / _ / -：$org/$repo"
        }
    }

    /** 相对路径 `repos/<org>/<repo>` */
    fun relativePath(): Path = Path.of("repos", org, repo)

    private companion object {
        val SEGMENT = Regex("[A-Za-z0-9._-]{1,100}")
    }
}