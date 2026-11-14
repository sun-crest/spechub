package io.spechub.server.ingest

import io.spechub.spec.index.SpecIndexStore
import io.spechub.server.config.SpecHubProperties
import io.spechub.server.web.RepoController
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.security.SecureRandom

/** E8 —— 首次启动初始化：目录（KernelConfig）+ DB 迁移 + 默认项目；`--seed-admin` 生成 admin 种子令牌 */
@Component
class ApplicationInit(
    private val index: SpecIndexStore,
    private val props: SpecHubProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        index.upsertProject(RepoController.DEFAULT_PROJECT)

        // admin 种子（P1 H6 UI 化前；支持环境注入 spechub.admin-token 或 --seed-admin 生成）
        val token = props.adminToken ?: if (args.sourceArgs.any { it == "--seed-admin" }) generateToken() else null
        if (token != null) {
            log.info("admin 令牌已就绪（接入端用 Authorization: Bearer <token>）：{}", token)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}