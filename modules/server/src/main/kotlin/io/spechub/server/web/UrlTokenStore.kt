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

    /** 注册时带 token：按 `oauth2:TOKEN@host` 形式注入后仅存内存（不落库） */
    fun putWithToken(repoId: Long, url: String, token: String) {
        urls[repoId] = injectToken(url.trim(), token.trim())
    }

    fun get(repoId: Long): String? = urls[repoId]

    fun remove(repoId: Long) {
        urls.remove(repoId)
    }

    companion object {
        /** `https://host/path` → `https://oauth2:TOKEN@host/path`（仅内存使用） */
        internal fun injectToken(url: String, token: String): String =
            if (url.contains("://")) {
                val proto = url.substringBefore("://")
                val rest = url.substringAfter("://")
                if (rest.contains("@")) url else "$proto://oauth2:$token@$rest"
            } else {
                url
            }
    }
}