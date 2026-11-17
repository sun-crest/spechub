package io.spechub.server.web

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * 集成测试的管理库：JVM 内共享一个 PostgreSQL 18.6 容器，
 * 每个测试类用独立 schema 隔离（Flyway 在各自 schema 建管理表）。
 *
 * 注意：Testcontainers 的 jdbcUrl 已带 `?loggerLevel=OFF`，追加参数必须用 `&`。
 */
object TestManagementDb {

    private val container: PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
            .withDatabaseName("spechub")
            .withUsername("spechub")
            .withPassword("spechub")

    fun register(registry: DynamicPropertyRegistry, schema: String) {
        if (!container.isRunning) container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
        }
        val sep = if (container.jdbcUrl.contains("?")) "&" else "?"
        registry.add("spring.datasource.url") { container.jdbcUrl + sep + "currentSchema=$schema" }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
        registry.add("spring.flyway.locations") { "classpath:db/migration/{vendor}" }
        registry.add("spring.flyway.schemas") { schema }
        registry.add("spring.flyway.default-schema") { schema }
        registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
    }
}
