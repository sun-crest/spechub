package io.spechub.server.web

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * 集成测试的管理库（双库矩阵）。
 *
 * 通过系统属性 `spechub.test.db` 选择引擎：`postgresql`（默认）或 `mysql`。
 * - PostgreSQL：JVM 内共享一个 18.6 容器，每个测试类用独立 schema。
 * - MySQL：共享一个 8.4.11 容器，每个测试类用独立 database（容器内 root 初始化并授权）。
 *
 * 注意：Testcontainers 的 jdbcUrl 可能已带参数，追加时必须判断分隔符。
 */
object TestManagementDb {
    private val vendor: String = System.getProperty("spechub.test.db", "postgresql")

    /** 独立库目标（Flyway 迁移测试等非 Spring 场景直接使用） */
    data class DbTarget(
        val jdbcUrl: String,
        val username: String,
        val password: String,
        val vendor: String,
        /** PostgreSQL 的隔离 schema；MySQL 为隔离 database（已在 URL 中） */
        val schema: String?,
    )

    private val pg: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
            .withDatabaseName("spechub")
            .withUsername("spechub")
            .withPassword("spechub")
            .apply { start() }
    }

    private val mysql: MySQLContainer<*> by lazy {
        MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("spechub")
            .withUsername("spechub")
            .withPassword("spechub")
            .withEnv("MYSQL_ROOT_PASSWORD", "root")
            .apply { start() }
    }

    fun register(
        registry: DynamicPropertyRegistry,
        schema: String,
    ) {
        val target = target(schema)
        registry.add("spring.datasource.url") { target.jdbcUrl }
        registry.add("spring.datasource.username") { target.username }
        registry.add("spring.datasource.password") { target.password }
        registry.add("spring.flyway.locations") { "classpath:db/migration/{vendor}" }
        if (target.vendor == "postgresql") {
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.flyway.default-schema") { schema }
        }
        registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
    }

    /** 创建独立 schema/database 并返回连接目标（幂等）。 */
    fun target(schema: String): DbTarget = if (vendor == "mysql") mysqlTarget(schema) else pgTarget(schema)

    private fun pgTarget(schema: String): DbTarget {
        val container = pg
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $schema") }
        }
        val sep = if (container.jdbcUrl.contains("?")) "&" else "?"
        return DbTarget(
            jdbcUrl = container.jdbcUrl + sep + "currentSchema=$schema",
            username = container.username,
            password = container.password,
            vendor = "postgresql",
            schema = schema,
        )
    }

    private fun mysqlTarget(schema: String): DbTarget {
        val container = mysql
        // 每类独立 database：容器内 root 创建并授权给容器用户（命中容器本地 root，不受远程 host 限制）
        val sql =
            "CREATE DATABASE IF NOT EXISTS $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; " +
                "CREATE USER IF NOT EXISTS 'spechub'@'%' IDENTIFIED BY 'spechub'; " +
                "GRANT ALL ON $schema.* TO 'spechub'@'%'; FLUSH PRIVILEGES;"
        // 容器内 shell 展开实际 root 密码（Testcontainers 会覆盖 MYSQL_ROOT_PASSWORD）
        val result = container.execInContainer("sh", "-c", "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"$sql\"")
        check(result.exitCode == 0) { "MySQL 测试库初始化失败：${result.stdout}\n${result.stderr}" }

        val base = container.jdbcUrl.substringBefore("?")
        val dbUrl = base.substringBeforeLast('/') + "/" + schema
        val url =
            "$dbUrl?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" +
                "&allowPublicKeyRetrieval=true&useSSL=false"
        return DbTarget(
            jdbcUrl = url,
            username = container.username,
            password = container.password,
            vendor = "mysql",
            schema = schema,
        )
    }
}
