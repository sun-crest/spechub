package io.spechub.server.service

import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/** 订阅/通知域迁入管理库后的集成验证。 */
@SpringBootTest
class SubscriptionServiceTest : TestDbIsolation() {
    @Autowired
    lateinit var repoService: RepoService

    @Autowired
    lateinit var subscriptionService: SubscriptionService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-subtest-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_subscription_svc")
        }
    }

    @Test
    fun `订阅幂等 + 通知历史不随取消清空`() {
        val repo = repoService.register("default", "file:///sub-${UUID.randomUUID()}", "main")

        val s1 = subscriptionService.subscribe(repo.id, null, "GET", "/pets", "pay-team")
        val s2 = subscriptionService.subscribe(repo.id, null, "GET", "/pets", "pay-team")
        assertEquals(s1.id, s2.id, "同 repo+method+path+team 幂等")
        assertEquals(1, subscriptionService.list(repo.id).size)

        subscriptionService.addNotification("pay-team", repo.id, "GET", "/pets", "结构变更 a")
        subscriptionService.addNotification("pay-team", repo.id, "GET", "/pets", "结构变更 b")
        assertEquals(2, subscriptionService.listNotifications("pay-team").size)
        assertEquals(1, subscriptionService.subscriptionsForEndpoint(repo.id, "GET", "/pets").size)

        subscriptionService.unsubscribe(checkNotNull(s1.id) { "订阅应有 id" })
        assertEquals(0, subscriptionService.list(repo.id).size, "取消订阅后查询为空")
        assertEquals(2, subscriptionService.listNotifications("pay-team").size, "通知历史不随订阅删除清空（审计）")
    }
}
