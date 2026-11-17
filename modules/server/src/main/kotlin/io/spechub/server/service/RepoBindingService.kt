package io.spechub.server.service

import io.spechub.server.repository.RepoBindingEntity
import io.spechub.server.repository.RepoBindingRepository
import io.spechub.server.repository.RepoEntity
import io.spechub.server.repository.RepoRepository
import io.spechub.spec.gstore.GitStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant

/**
 * 真源绑定 Service（ADR §4.4，single-primary binding）。
 *
 * - 同一 repo 至多一条 active；`activate` 事务内把旧 active 退休、候选转正。
 * - `verify` 先用元数据短路，必要时 clone 两侧做文件级比对（A7 暴露为 API）。
 * - 本 Service 不执行摄取；flip 后由调用方触发全量重建索引。
 */
@Service
class RepoBindingService(
    private val bindings: RepoBindingRepository,
    private val repos: RepoRepository,
    private val git: GitStore,
) {
    data class VerifyResult(
        val status: String,
        val candidateId: Long,
        val activeId: Long?,
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val changed: List<String> = emptyList(),
        val message: String? = null,
    )

    companion object {
        const val VERIFY_IDENTICAL = "identical"
        const val VERIFY_DIVERGED = "diverged"
        const val VERIFY_NO_ACTIVE = "no_active"
        const val VERIFY_ERROR = "error"
    }

    fun list(repoId: Long): List<RepoBindingEntity> = bindings.findAllByRepoIdOrderByIdAsc(repoId)

    fun active(repoId: Long): RepoBindingEntity? = bindings.findByRepoIdAndStatus(repoId, RepoBindingEntity.STATUS_ACTIVE)

    fun get(
        repoId: Long,
        bindingId: Long,
    ): RepoBindingEntity = requireBinding(repoId, bindingId)

    /** 新仓库注册时创建首条 active binding（managed）。 */
    @Transactional
    fun createActiveManaged(
        repo: RepoEntity,
        externalRepoId: String,
    ): RepoBindingEntity {
        val repoId = checkNotNull(repo.id) { "repo 必有 id" }
        bindings.findByRepoIdAndStatus(repoId, RepoBindingEntity.STATUS_ACTIVE)?.let { return it }
        return bindings.save(
            RepoBindingEntity(
                repoId = repoId,
                kind = RepoBindingEntity.KIND_MANAGED,
                externalRepoId = externalRepoId,
                remoteUrl = repo.url,
                branch = repo.branch,
                status = RepoBindingEntity.STATUS_ACTIVE,
                createdAt = Instant.now(),
            ),
        )
    }

    /** 旧仓库（A1 前注册）没有 binding 时补齐一条 active。 */
    @Transactional
    fun ensureActiveManaged(repo: RepoEntity) {
        val repoId = checkNotNull(repo.id) { "repo 必有 id" }
        if (bindings.findByRepoIdAndStatus(repoId, RepoBindingEntity.STATUS_ACTIVE) != null) return
        val externalRepoId = repo.externalRepoId ?: md5(repo.url)
        createActiveManaged(repo, externalRepoId)
    }

    /** 创建迁移候选；不自动验证/激活。 */
    @Transactional
    fun createCandidate(
        repoId: Long,
        kind: String,
        remoteUrl: String,
        branch: String = "main",
        connectionId: Long? = null,
        externalRepoId: String? = null,
    ): RepoBindingEntity {
        require(repos.findById(repoId).isPresent) { "repo $repoId 不存在" }
        require(kind == RepoBindingEntity.KIND_MANAGED || kind == RepoBindingEntity.KIND_EXTERNAL) {
            "kind ∈ ${RepoBindingEntity.KIND_MANAGED}|${RepoBindingEntity.KIND_EXTERNAL}"
        }
        return bindings.save(
            RepoBindingEntity(
                repoId = repoId,
                kind = kind,
                connectionId = connectionId,
                externalRepoId = externalRepoId,
                remoteUrl = remoteUrl.trim(),
                branch = branch,
                status = RepoBindingEntity.STATUS_CANDIDATE,
                createdAt = Instant.now(),
            ),
        )
    }

    /**
     * 候选转正：旧 active → retired，候选 → active。
     * 并发 flip 由数据库单主唯一约束兜底（调用方映射 409）。
     */
    @Transactional
    fun activate(
        repoId: Long,
        bindingId: Long,
    ): RepoBindingEntity {
        val candidate = requireBinding(repoId, bindingId)
        if (candidate.status == RepoBindingEntity.STATUS_ACTIVE) return candidate
        require(candidate.status == RepoBindingEntity.STATUS_CANDIDATE) {
            "只有 candidate 可激活（当前 ${candidate.status}）"
        }
        val now = Instant.now()
        bindings.findByRepoIdAndStatus(repoId, RepoBindingEntity.STATUS_ACTIVE)?.let {
            bindings.save(it.copy(status = RepoBindingEntity.STATUS_RETIRED, retiredAt = now))
        }
        return bindings.save(candidate.copy(status = RepoBindingEntity.STATUS_ACTIVE, retiredAt = null))
    }

    /** 退休非 active 绑定；active 必须先 flip 到新 active。 */
    @Transactional
    fun retire(
        repoId: Long,
        bindingId: Long,
    ): RepoBindingEntity {
        val binding = requireBinding(repoId, bindingId)
        require(binding.status != RepoBindingEntity.STATUS_ACTIVE) {
            "active binding 不能直接退休；先 flip 到新 active"
        }
        if (binding.status == RepoBindingEntity.STATUS_RETIRED) return binding
        return bindings.save(binding.copy(status = RepoBindingEntity.STATUS_RETIRED, retiredAt = Instant.now()))
    }

    /** 只允许删除已退休绑定（回滚窗口结束后）。 */
    @Transactional
    fun deleteRetired(
        repoId: Long,
        bindingId: Long,
    ) {
        val binding = requireBinding(repoId, bindingId)
        require(binding.status == RepoBindingEntity.STATUS_RETIRED) { "只能删除 retired binding" }
        bindings.deleteById(bindingId)
    }

    /**
     * 验证候选与当前 active 的一致性。
     * 元数据相同直接判 identical；否则 clone 两侧比对文件内容。
     */
    fun verify(
        repoId: Long,
        bindingId: Long,
    ): VerifyResult {
        val candidate = requireBinding(repoId, bindingId)
        val active =
            bindings.findByRepoIdAndStatus(repoId, RepoBindingEntity.STATUS_ACTIVE)
                ?: return VerifyResult(VERIFY_NO_ACTIVE, checkNotNull(candidate.id), null, message = "仓库没有 active binding")
        val candidateId = checkNotNull(candidate.id) { "binding 必有 id" }
        val activeId = checkNotNull(active.id) { "binding 必有 id" }
        if (candidateId == activeId) return VerifyResult(VERIFY_IDENTICAL, candidateId, activeId)
        if (candidate.remoteUrl == active.remoteUrl &&
            candidate.branch == active.branch &&
            candidate.externalRepoId == active.externalRepoId
        ) {
            return VerifyResult(VERIFY_IDENTICAL, candidateId, activeId)
        }
        return try {
            val candidateFiles = snapshot(candidate)
            val activeFiles = snapshot(active)
            val added = candidateFiles.keys - activeFiles.keys
            val removed = activeFiles.keys - candidateFiles.keys
            val changed = candidateFiles.keys.intersect(activeFiles.keys).filter { candidateFiles[it] != activeFiles[it] }
            val status = if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) VERIFY_IDENTICAL else VERIFY_DIVERGED
            VerifyResult(
                status = status,
                candidateId = candidateId,
                activeId = activeId,
                added = added.sorted(),
                removed = removed.sorted(),
                changed = changed.sorted(),
            )
        } catch (e: Exception) {
            VerifyResult(
                status = VERIFY_ERROR,
                candidateId = candidateId,
                activeId = activeId,
                message = e.message ?: "verify failed",
            )
        }
    }

    private fun requireBinding(
        repoId: Long,
        bindingId: Long,
    ): RepoBindingEntity {
        val binding = bindings.findById(bindingId).orElse(null) ?: throw IllegalArgumentException("binding $bindingId 不存在")
        require(binding.repoId == repoId) { "binding $bindingId 不属于 repo $repoId" }
        return binding
    }

    /** 文件级快照：path → 内容。git 操作不放进管理库事务。 */
    private fun snapshot(binding: RepoBindingEntity): Map<String, String> {
        val root = Files.createTempDirectory("spechub-verify-")
        try {
            val dir = root.resolve("repo")
            git.cloneInto(binding.remoteUrl, dir, binding.branch).require("clone ${binding.remoteUrl}")
            return git.listFiles(dir, "HEAD").associateWith { path ->
                git.readFile(dir, "HEAD", path).require("read $path").stdout
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun md5(text: String): String =
        MessageDigest
            .getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
