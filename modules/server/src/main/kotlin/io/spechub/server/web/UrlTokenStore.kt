package io.spechub.server.web

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/*
 * E2 —— token 凭据内存暂存（不落库，§0 E2 决策）。
 * 注册时若带 token，注入 `https://oauth2:TOKEN@host/…` 后的 URL 只存内存；
 * fetch/clone 命令参数用它，git remote config 已被 GitStore 清洗。
 */

@Component
class UrlTokenStore {
    private val urls = ConcurrentHashMap<Long, String>()

    fun put(repoId: Long, effectiveUrl: String) {
        urls[repoId] = effectiveUrl
    }

    fun get(repoId: Long): String? = urls[repoId]

    fun remove(repoId: Long) {
        urls.remove(repoId)
    }
}