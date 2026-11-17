package io.spechub.server.config

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.context.annotation.Configuration
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R10：配置绑定期校验——dev 允许缺省 admin-token，prod 缺失必须启动失败（而非运行期降级）。 */
class SpecHubPropertiesValidationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpecHubProperties::class)
    class PropsConfig

    private val runner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(
                AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration::class.java,
                    ValidationAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(PropsConfig::class.java)

    @Test
    fun `dev profile 未配置 admin token 可启动`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "spechub.admin-token=",
            ).run { context ->
                assertNull(context.startupFailure, "dev 不应因缺省 admin token 启动失败")
                assertNotNull(context.getBean(SpecHubProperties::class.java))
            }
    }

    @Test
    fun `prod profile 未配置 admin token 启动失败`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "spechub.admin-token=",
            ).run { context ->
                val failure = assertNotNull(context.startupFailure, "prod 缺 admin token 应启动失败")
                val messages =
                    generateSequence(failure) { it.cause }
                        .mapNotNull { it.message }
                        .joinToString("\n")
                assertTrue(
                    messages.contains("admin-token") || messages.contains("adminToken"),
                    "错误信息应指明 admin token：$messages",
                )
            }
    }
}
