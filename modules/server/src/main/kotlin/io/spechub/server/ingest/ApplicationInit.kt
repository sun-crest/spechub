package io.spechub.server.ingest

import io.spechub.spec.index.PlatformStore
import io.spechub.server.config.SpecHubProperties
import io.spechub.server.web.AuthService
import io.spechub.server.web.RepoController
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** E8 —— 首次启动初始化：目录（KernelConfig）+ DB 迁移 + 默认项目 + admin 种子（I4 RBAC bootstrap） */
@Component
class ApplicationInit(
    private val platform: PlatformStore,
    private val props: SpecHubProperties,
    private val auth: AuthService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        platform.upsertProject(RepoController.DEFAULT_PROJECT)

        // admin 种子（I4）：配置 spechub.admin-token 时 bootstrap 平台 admin（长驻 app_user，token 只存 hash）
        val admin = auth.bootstrapAdmin()
        if (admin != null) {
            val source = if (props.adminToken != null) "SPECHUB_ADMIN_TOKEN" else "--seed-admin"
            log.info("平台 admin「{}」已就绪（RBAC 角色 owner@所有项目；启用鉴权）。来源：{}", admin.username, source)
        } else {
            log.info("未配置 spechub.admin-token —— 鉴权未启用（全放行，本地开发模式）。配置后启动即为平台 admin（owner）。")
        }
    }
}