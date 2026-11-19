package io.spechub.server.security

import io.spechub.server.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsPasswordService
import org.springframework.stereotype.Service
import java.time.Instant

/** 登录成功时按当前 PasswordEncoder 参数升级旧哈希（Argon2 参数升级 / 未来迁移）。 */
@Service
class AppUserDetailsPasswordService(
    private val users: UserRepository,
) : UserDetailsPasswordService {
    override fun updatePassword(
        user: UserDetails,
        newPassword: String?,
    ): UserDetails {
        if (newPassword == null) return user
        val principal = user as? AppUserPrincipal ?: return user
        val entity = users.findById(principal.userId).orElse(null) ?: return user
        val saved = users.save(entity.copy(passwordHash = newPassword, passwordUpdatedAt = Instant.now()))
        return saved.toPrincipal()
    }
}
