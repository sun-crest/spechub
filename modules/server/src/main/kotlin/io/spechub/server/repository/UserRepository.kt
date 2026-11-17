package io.spechub.server.repository

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface UserRepository : CrudRepository<UserEntity, Long> {
    /** 用户名不敏感匹配（§8.2）：PG 走 lower() 唯一索引，MySQL 走 CI collation，两库语义一致 */
    @Query("SELECT * FROM app_user WHERE lower(username) = lower(:username)")
    fun findByUsername(username: String): UserEntity?

    fun findByTokenHash(tokenHash: String): UserEntity?

    @Query("SELECT * FROM app_user ORDER BY is_platform_admin DESC, id")
    fun findAllOrdered(): List<UserEntity>
}
