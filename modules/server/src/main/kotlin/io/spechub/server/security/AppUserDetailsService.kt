package io.spechub.server.security

import io.spechub.server.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/** 密码登录：按 username 或 email（忽略大小写）加载用户。 */
@Service
class AppUserDetailsService(
    private val users: UserRepository,
) : UserDetailsService {
    override fun loadUserByUsername(identifier: String): UserDetails {
        val key = identifier.trim()
        if (key.isEmpty()) throw UsernameNotFoundException("用户名或邮箱为空")
        val entity =
            users.findByUsername(key)
                ?: users.findByEmail(key)
                ?: throw UsernameNotFoundException("用户不存在")
        return entity.toPrincipal()
    }
}
