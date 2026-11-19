package io.spechub.server.repository

import org.springframework.data.repository.CrudRepository

interface UserIdentityRepository : CrudRepository<UserIdentityEntity, Long> {
    fun findByProviderAndSubject(
        provider: String,
        subject: String,
    ): UserIdentityEntity?

    fun findAllByUserId(userId: Long): List<UserIdentityEntity>
}
