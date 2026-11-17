package io.spechub.spike.persistence

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 同一组断言跑在两个引擎上：子类分别提供 DataSource（PG schema / MySQL database 隔离）。
 * 验证：不可变 data class 构造器映射、派生查询、@Version、Instant UTC 往返、唯一约束。
 */
abstract class AbstractPersistenceSpikeTest {

    @Autowired
    lateinit var repo: UserRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun sample(username: String, admin: Boolean = false) = SpikeUser(
        username = username,
        displayName = "姓名-$username",
        isAdmin = admin,
        createdAt = Instant.parse("2026-11-17T04:00:00.123456Z"),
    )

    @BeforeEach
    fun clean() {
        // 同一 context 内多个用例共享 schema/database：每个用例前清表，断言互不影响
        jdbc.update("DELETE FROM spike_user")
    }

    @Test
    fun `Flyway 按 vendor 建表成功`() {
        val count = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Long::class.java)
        assertTrue((count ?: 0) >= 1, "Flyway 应记录迁移历史")
        assertEquals(0, jdbc.queryForList("SELECT * FROM spike_user").size)
    }

    @Test
    fun `不可变 data class 构造器映射 + 派生查询 + 布尔与 Instant`() {
        val saved = repo.save(sample("alice", admin = true))
        assertNotNull(saved.id)

        val loaded = assertNotNull(repo.findByUsername("alice"))
        assertEquals(saved.id, loaded.id)
        assertTrue(loaded.isAdmin, "BOOLEAN / TINYINT(1) 映射")
        assertEquals("姓名-alice", loaded.displayName)
        assertEquals(Instant.parse("2026-11-17T04:00:00.123456Z"), loaded.createdAt, "Instant 微秒精度 UTC 往返")
        assertEquals(listOf(saved.id), repo.findAllByIsAdminTrue().map { it.id }, "派生布尔查询")
        assertEquals(listOf(saved.id), repo.byDisplayName("姓名-alice").map { it.id }, "@Query 自定义查询")
    }

    @Test
    fun `不可变实体更新与 @Version 乐观锁`() {
        val saved = assertNotNull(repo.save(sample("bob")))
        val initialVersion = assertNotNull(saved.version)

        val updated = repo.save(saved.copy(displayName = "Bob-2"))
        assertEquals("Bob-2", repo.findById(assertNotNull(updated.id)).orElseThrow().displayName)

        // 旧版本（过期实体）更新必须失败
        assertFailsWith<OptimisticLockingFailureException> { repo.save(saved.copy(displayName = "stale")) }
        println("SPIKE version: initial=$initialVersion updated=${updated.version}")
    }

    @Test
    fun `唯一约束冲突映射为 DataIntegrityViolationException`() {
        repo.save(sample("carol"))
        assertFailsWith<DataIntegrityViolationException> { repo.save(sample("carol")) }
    }
}
