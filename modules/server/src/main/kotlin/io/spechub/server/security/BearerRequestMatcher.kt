package io.spechub.server.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.security.web.util.matcher.RequestMatcher

/** 仅当请求带 `Authorization: Bearer ...` 时命中，避免 Session / Bearer 两套策略互相污染。 */
class BearerRequestMatcher : RequestMatcher {
    override fun matches(request: HttpServletRequest): Boolean {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return false
        return header.length > 7 && header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)
    }
}
