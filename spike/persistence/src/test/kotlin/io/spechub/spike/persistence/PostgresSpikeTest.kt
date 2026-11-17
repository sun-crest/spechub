package io.spechub.spike.persistence

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.DriverManager

/** PostgreSQL 18.6：共享容器 + 独立 schema 隔离。 */
@SpringBootTest
class PostgresSpikeTest : AbstractPersistenceSpikeTest() {

    companion object {
        private const val SCHEMA = "spike_pg"

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val container = SharedContainers.pg
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS $SCHEMA") }
            }
            val sep = if (container.jdbcUrl.contains("?")) "&" else "?"
            val url = container.jdbcUrl + sep + "currentSchema=$SCHEMA"
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { container.username }
            registry.add("spring.datasource.password") { container.password }
            registry.add("spring.flyway.locations") { "classpath:db/migration/{vendor}" }
            registry.add("spring.flyway.schemas") { SCHEMA }
            registry.add("spring.flyway.default-schema") { SCHEMA }
            registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
        }
    }
}
