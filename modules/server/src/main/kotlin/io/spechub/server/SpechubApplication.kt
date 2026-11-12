package io.spechub.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SpechubApplication

fun main(args: Array<String>) {
    runApplication<SpechubApplication>(*args)
}