package io.spechub.server.service

import io.spechub.server.repository.AuditEventEntity
import io.spechub.server.repository.AuditEventRepository
import io.spechub.server.web.TestDbIsolation
import io.spechub.server.web.TestManagementDb
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C7：审计保留策略——超过 365 天的记录清理，未过期保留。 */
@SpringBootTest
class AuditRetentionTest : TestDbIsolation() {
    @Autowired
    lateinit var retention: AuditRetentionService

    @Autowired
    lateinit var events: AuditEventRepository

    @Autowired
    lateinit var orgs: OrgService

    companion object {
        private val TEMPDIR: Path = createTempDirectory("spechub-audit-retention-" + System.nanoTime())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spechub.git-source-dir") { TEMPDIR.resolve("gitsource").toString() }
            registry.add("spechub.sqlite-dir") { TEMPDIR.resolve("sqlite").toString() }
            registry.add("spechub.artifacts-dir") { TEMPDIR.resolve("artifacts").toString() }
            TestManagementDb.register(registry, "t_audit_retention")
        }
    }

    private fun event(createdAt: Instant): Long {
        val saved =
            events.save(
                AuditEventEntity(
                    orgId = orgs.defaultOrgId(),
                    actorName = "retention",
                    actorType = "user",
                    action = "retention.test",
                    outcome = "success",
                    createdAt = createdAt,
                ),
            )
        return assertNotNull(saved.id)
    }

    @Test
    fun `超过保留期清理 - 未过期保留`() {
        val oldId = event(Instant.now().minus(Duration.ofDays(400)))
        val newId = event(Instant.now().minus(Duration.ofDays(1)))

        val deleted = retention.cleanup()
        assertTrue(deleted >= 1, "应清理过期记录")
        assertTrue(events.findById(oldId).isEmpty, "400 天前的记录应被清理")
        assertEquals(true, events.findById(newId).isPresent, "1 天前的记录应保留")
    }
}
