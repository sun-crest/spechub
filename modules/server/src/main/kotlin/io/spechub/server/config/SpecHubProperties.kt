package io.spechub.server.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** SPECHUB_* 配置绑定（E1）。默认值对应当地演播态：./data 下 gitsource 与索引库。 */
@ConfigurationProperties(prefix = "spechub")
data class SpecHubProperties(
    /** gitsource 根：镜像仓落盘目录（§7.3 gitsource/repos/<org>/<repo>）。 */
    val gitsourcePath: String = "data/gitsource",
    /** SQLite 索引库文件。 */
    val dbPath: String = "data/spechub.db",
) {
    val gitsourceDir: java.nio.file.Path get() = java.nio.file.Path.of(gitsourcePath)
    val indexDbFile: java.nio.file.Path get() = java.nio.file.Path.of(dbPath)
}