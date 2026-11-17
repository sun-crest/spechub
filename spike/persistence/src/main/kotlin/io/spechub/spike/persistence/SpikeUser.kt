package io.spechub.spike.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.time.Instant

/**
 * 验证目标：Kotlin 不可变 data class 能否直接被 Spring Data JDBC 构造器映射。
 * 不依赖 no-arg / all-open；主构造器参数名由 kotlin-reflect 发现。
 */
@Table("spike_user")
data class SpikeUser(
    @Id val id: Long? = null,
    val username: String,
    @Column("display_name") val displayName: String,
    @Column("is_admin") val isAdmin: Boolean,
    @Version val version: Long? = null,
    val createdAt: Instant,
)

interface UserRepository : CrudRepository<SpikeUser, Long> {
    /** 派生查询：username → username，验证属性名 → 列名推导 */
    fun findByUsername(username: String): SpikeUser?

    /** 派生查询 + 布尔属性（isAdmin → is_admin） */
    fun findAllByIsAdminTrue(): List<SpikeUser>

    /** 自定义 SQL 下探（验证 JdbcTemplate 之外 @Query 可用） */
    @Query("SELECT * FROM spike_user WHERE display_name = :name")
    fun byDisplayName(name: String): List<SpikeUser>
}
