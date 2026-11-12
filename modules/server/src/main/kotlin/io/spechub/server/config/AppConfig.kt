package io.spechub.server.config

import io.spechub.spec.gstore.GitStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfig {

    @Bean
    fun gitStore(props: SpecHubProperties): GitStore = GitStore(props.gitsourceDir)
}