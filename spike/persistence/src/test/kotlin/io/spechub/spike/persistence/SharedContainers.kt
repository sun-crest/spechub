package io.spechub.spike.persistence

import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 共享容器（JVM 内单例）：两个测试类复用同一 PG / MySQL 实例，
 * 各自用独立 schema / database 隔离数据。镜像 tag 固定小版本，避免漂移。
 */
object SharedContainers {

    val pg: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
        .withDatabaseName("spechub")
        .withUsername("spechub")
        .withPassword("spechub")

    val mysql: MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
        .withDatabaseName("spechub")
        .withUsername("spechub")
        .withPassword("spechub")
        .withEnv("MYSQL_ROOT_PASSWORD", "root")

    init {
        pg.start()
        mysql.start()
    }
}
