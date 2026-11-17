package io.spechub.server.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.nio.file.Path

/*
 * E1 —— `SPECHUB_*` 环境变量 → 绑定（Spring relaxed binding：SPECHUB_GIT_SOURCE_DIR → spechub.git-source-dir → gitSourceDir）。
 * 默认值对应 compose/docker-compose.yml 的卷挂载点约定（见 A4）。
 *
 * R10：加 `@Validated` + 声明式必填校验；`require-admin-token` 由 prod profile 置 true，
 * 缺失/空串 admin-token 在配置绑定阶段即启动失败（不会运行期静默降级为「鉴权关闭」）。
 */

@Validated
@ConfigurationProperties(prefix = "spechub")
data class SpecHubProperties(
    /** 真源文档仓镜像根（gitsource 卷）：`<gitSourceDir>/repos/<org>_<repo>`（§7.3） */
    @field:NotBlank val gitSourceDir: String = defaultDir("gitsource"),
    /** SQLite 索引目录（sqlite 卷） */
    @field:NotBlank val sqliteDir: String = defaultDir("sqlite"),
    /** 导出/门户产物目录（artifacts 卷） */
    @field:NotBlank val artifactsDir: String = defaultDir("artifacts"),
    /** 用户可访问的部署基址 */
    @field:NotBlank val externalUrl: String = "http://localhost:8080",
    /** 内置 Gitea 地址（compose 网络内为 http://gitea:3000） */
    @field:NotBlank val giteaUrl: String = "http://localhost:3000",
    /** 轮询兜底间隔（E7） */
    @field:Min(1) val pollingIntervalMs: Long = 60_000,
    /** 默认扫描分支 */
    @field:NotBlank val defaultBranch: String = "main",
    /** admin 种子令牌（P1 H6 UI 化前，提供命令行/环境注入） */
    val adminToken: String? = null,
    /** prod profile 置 true：admin token 缺失/空串即启动失败（R10） */
    val requireAdminToken: Boolean = false,
    // R11 文档端点是否公开：dev 默认 true，放行 api-docs / doc.html / webjars；
    // prod 置 false 并关闭 springdoc/knife4j，文档仍受 TokenAuthFilter 保护（401）。
    val openapiPublic: Boolean = true,
) {
    fun gitSourcePath(): Path = Path.of(gitSourceDir)

    fun sqlitePath(): Path = Path.of(sqliteDir)

    fun artifactsPath(): Path = Path.of(artifactsDir)

    /** 生产安全项校验：dev 允许缺省（鉴权关闭），prod 必须显式提供非空 token。 */
    @AssertTrue(message = "生产环境必须显式配置 spechub.admin-token（SPECHUB_ADMIN_TOKEN），否则鉴权关闭")
    fun isAdminTokenConfiguredWhenRequired(): Boolean = !requireAdminToken || !adminToken.isNullOrBlank()

    companion object {
        private fun defaultDir(name: String) = "${System.getProperty("user.dir")}/.spechub/$name"
    }
}
