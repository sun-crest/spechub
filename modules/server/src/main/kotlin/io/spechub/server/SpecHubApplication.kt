package io.spechub.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * SpecHub server 入口（Spring Boot 4 聚合层）。
 *
 * P0 骨架阶段为最小可启动空壳；`SPECHUB_*` 配置绑定与单测基建在开发计划 E1 补齐，
 * 摄取 / 索引 / REST 能力随 E3+ 落地。
 */
@SpringBootApplication
class SpecHubApplication

fun main(args: Array<String>) {
    runApplication<SpecHubApplication>(*args)
}