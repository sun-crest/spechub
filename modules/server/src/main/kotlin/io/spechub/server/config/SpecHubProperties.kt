package io.spechub.server.config

import jakarta.validation.Valid
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
 * 阶段 C：所有环境都必须真实登录（`require-admin-token` 恒 true）；admin-token 只用于
 * bootstrap / break-glass，不再是「启用鉴权」的开关。缺失/空串在配置绑定阶段即启动失败。
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
    /** bootstrap / break-glass admin 令牌（所有环境必填；不再代表“是否启用鉴权”） */
    val adminToken: String? = null,
    /** 阶段 C 起恒 true：未配置 admin-token 启动失败（与 profile 无关） */
    val requireAdminToken: Boolean = true,
    /** 会话 Cookie 是否加 Secure（prod 必须 true；dev HTTP 可配 false） */
    val sessionCookieSecure: Boolean = true,
    /** 会话绝对超时（秒），默认 7 天；空闲超时由 spring.session.timeout 控制 */
    @field:Min(60) val sessionAbsoluteTimeoutSeconds: Long = 7L * 24 * 3600,
    /** binding flip 后旧 binding 的回滚窗口（天）：窗口内禁止删除 retired binding（ADR §4.4） */
    @field:Min(0) val repoBindingRollbackDays: Long = 30,
    // R11 文档端点是否公开：dev 默认 true，放行 api-docs / doc.html / webjars；
    // prod 置 false 并关闭 springdoc/knife4j，文档仍受鉴权保护（401）。
    val openapiPublic: Boolean = true,
    /** OIDC 作为 Web 链的附加登录方式；enabled=false 时不注册任何 OIDC bean。 */
    @field:Valid val oidc: OidcProperties = OidcProperties(),
) {
    fun gitSourcePath(): Path = Path.of(gitSourceDir)

    fun sqlitePath(): Path = Path.of(sqliteDir)

    fun artifactsPath(): Path = Path.of(artifactsDir)

    /** 所有环境都必须显式配置非空 admin-token。 */
    @AssertTrue(message = "必须显式配置 spechub.admin-token（SPECHUB_ADMIN_TOKEN），用于首次 bootstrap / break-glass")
    fun isAdminTokenConfigured(): Boolean = !adminToken.isNullOrBlank()

    companion object {
        private fun defaultDir(name: String) = "${System.getProperty("user.dir")}/.spechub/$name"
    }
}

/**
 * OIDC 单 IdP 配置（C6）。作为 Web 链 `oauth2Login` 的附加登录方式，不影响 Bearer API 链。
 *
 * - `enabled=false`：不注册 `ClientRegistrationRepository`，登录页不显示 SSO；
 * - `issuer-uri` 默认用于启动发现；若 IdP 不允许启动外联，可显式提供
 *   `authorization-uri` / `token-uri` / `jwk-set-uri`（仍以 issuer-uri 作为身份 provider 标识）；
 * - `client-secret` 只经环境变量注入，不写日志。
 */
data class OidcProperties(
    val enabled: Boolean = false,
    @field:NotBlank val registrationId: String = "spechub",
    val issuerUri: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scopes: List<String> = listOf("openid", "email", "profile"),
    /** 空 = `${external-url}/login/oauth2/code/<registration-id>` */
    val redirectUri: String? = null,
    val autoProvision: Boolean = false,
    @field:NotBlank val defaultOrgSlug: String = "default",
    @field:NotBlank val emailClaim: String = "email",
    val requireVerifiedEmail: Boolean = true,
    val authorizationUri: String? = null,
    val tokenUri: String? = null,
    val jwkSetUri: String? = null,
    val userInfoUri: String? = null,
) {
    @AssertTrue(message = "启用 OIDC 时必须配置 spechub.oidc.issuer-uri / client-id / client-secret")
    fun isConfiguredWhenEnabled(): Boolean =
        !enabled ||
            (!issuerUri.isNullOrBlank() && !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank())

    fun resolvedRedirectUri(externalUrl: String): String =
        redirectUri?.takeIf { it.isNotBlank() }
            ?: "${externalUrl.trimEnd('/')}/login/oauth2/code/$registrationId"

    /** 显式端点齐全时跳过 issuer 发现。 */
    fun hasExplicitEndpoints(): Boolean = !authorizationUri.isNullOrBlank() && !tokenUri.isNullOrBlank() && !jwkSetUri.isNullOrBlank()
}
