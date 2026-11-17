package io.spechub.spike.persistence

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** MySQL 8.4.11：容器自带库/用户；URL 强制 UTC 会话时区。 */
@SpringBootTest
class MysqlSpikeTest : AbstractPersistenceSpikeTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            val container = SharedContainers.mysql
            val params = "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" +
                "&allowPublicKeyRetrieval=true&useSSL=false"
            val url = container.jdbcUrl + (if (container.jdbcUrl.contains("?")) "&" else "?") + params
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { container.username }
            registry.add("spring.datasource.password") { container.password }
            registry.add("spring.flyway.locations") { "classpath:db/migration/{vendor}" }
            registry.add("spring.datasource.hikari.maximum-pool-size") { "2" }
        }
    }
}
