package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface UserTokenRepository : CrudRepository<UserTokenEntity, Long> {
    fun findByTokenHash(tokenHash: String): UserTokenEntity?

    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<UserTokenEntity>

    fun findByIdAndUserId(
        id: Long,
        userId: Long,
    ): UserTokenEntity?

    fun deleteAllByUserId(userId: Long): Int

    fun deleteAllByUserIdAndPurpose(
        userId: Long,
        purpose: String,
    ): Int
}
