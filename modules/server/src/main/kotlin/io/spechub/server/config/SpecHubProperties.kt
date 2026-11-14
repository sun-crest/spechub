package io.spechub.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/*
 * E1 —— `SPECHUB_*` 环境变量 → 绑定（Spring relaxed binding：SPECHUB_GIT_SOURCE_DIR → spechub.git-source-dir → gitSourceDir）。
 * 默认值对应 compose/docker-compose.yml 的卷挂载点约定（见 A4）。
 */

@ConfigurationProperties(prefix = "spechub")
data class SpecHubProperties(
    /** 真源文档仓镜像根（gitsource 卷）：`<gitSourceDir>/repos/<org>_<repo>`（§7.3） */
    val gitSourceDir: String = defaultDir("gitsource"),
    /** SQLite 索引目录（sqlite 卷） */
    val sqliteDir: String = defaultDir("sqlite"),
    /** 导出/门户产物目录（artifacts 卷） */
    val artifactsDir: String = defaultDir("artifacts"),
    /** 用户可访问的部署基址 */
    val externalUrl: String = "http://localhost:8080",
    /** 内置 Gitea 地址（compose 网络内为 http://gitea:3000） */
    val giteaUrl: String = "http://localhost:3000",
    /** 轮询兜底间隔（E7） */
    val pollingIntervalMs: Long = 60_000,
    /** 默认扫描分支 */
    val defaultBranch: String = "main",
    /** admin 种子令牌（P1 H6 UI 化前，提供命令行/环境注入） */
    val adminToken: String? = null,
) {
    fun gitSourcePath(): Path = Path.of(gitSourceDir)
    fun sqlitePath(): Path = Path.of(sqliteDir)
    fun artifactsPath(): Path = Path.of(artifactsDir)

    companion object {
        private fun defaultDir(name: String) = "${System.getProperty("user.dir")}/.spechub/$name"
    }
}