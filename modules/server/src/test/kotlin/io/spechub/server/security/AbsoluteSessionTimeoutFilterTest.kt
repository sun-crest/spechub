package io.spechub.server.security

import jakarta.servlet.http.HttpSession
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** C2：绝对会话超时（默认 7 天）由 filter 校验，超时返回 401 并丢弃会话。 */
class AbsoluteSessionTimeoutFilterTest {
    private val entryPoint = ApiAuthenticationEntryPoint(JsonMapper.builder().build())

    private fun requestWithSessionAge(ageMillis: Long): Pair<MockHttpServletRequest, HttpSession> {
        val session = Mockito.mock(HttpSession::class.java)
        Mockito.`when`(session.creationTime).thenReturn(System.currentTimeMillis() - ageMillis)
        val request = MockHttpServletRequest()
        request.setSession(session)
        return request to session
    }

    @Test
    fun `超过绝对有效期返回 401 且不继续过滤链`() {
        val (request, _) = requestWithSessionAge(61_000)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        AbsoluteSessionTimeoutFilter(60, entryPoint).doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(chain.request, "超时请求不应进入后续链")
    }

    @Test
    fun `未超过绝对有效期继续过滤链`() {
        val (request, _) = requestWithSessionAge(1_000)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        AbsoluteSessionTimeoutFilter(60, entryPoint).doFilter(request, response, chain)

        assertNotNull(chain.request, "未超时应继续过滤链")
        assertEquals(200, response.status)
    }
}
