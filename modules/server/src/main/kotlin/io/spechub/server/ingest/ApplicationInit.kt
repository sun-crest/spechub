package io.spechub.server.ingest

import io.spechub.server.config.SpecHubProperties
import io.spechub.server.service.ProjectService
import io.spechub.server.service.RepoService
import io.spechub.server.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * E8 —— 首次启动初始化：目录（KernelConfig）+ DB 迁移 + 默认项目 + admin 引导。
 *
 * 阶段 C 起所有环境都必须配置 admin-token（配置绑定阶段校验）；admin-token 只用于 bootstrap /
 * break-glass，登录成功同样换取 Spring Session。不覆盖 admin 的 `status` / `password_hash`：
 * 被禁用的 admin 重启后仍保持禁用。
 */
@Component
class ApplicationInit(
    private val repoService: RepoService,
    private val projectService: ProjectService,
    private val props: SpecHubProperties,
    private val users: UserService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        repoService.ensureProject(ProjectService.DEFAULT_PROJECT)

        val adminToken = checkNotNull(props.adminToken?.takeIf { it.isNotBlank() }) { "admin-token 必须非空（配置校验应已拦截）" }
        val admin = users.bootstrapAdmin(adminToken)
        admin.id?.let { projectService.ensureDefaultProjectOwner(it) }
        log.info("平台 admin「{}」已就绪（org_admin；legacy 令牌已刷新）。admin-token 仅用于 bootstrap / break-glass。", admin.username)
    }
}
