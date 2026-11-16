package io.spechub.server.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R9：请求访问日志只记 method/path/status/durationMs，不泄漏请求体、Authorization 或 query string。 */
class RequestLogFilterTest {

    @Test
    fun `每个请求一条访问日志且不记 body 与 token`() {
        val logger = LoggerFactory.getLogger(RequestLogFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val req = MockHttpServletRequest("POST", "/api/repos")
            req.queryString = "token=secret-token-123"
            req.addHeader("Authorization", "Bearer secret-token-123")
            req.setContent("""{"token":"secret-token-123"}""".toByteArray())
            val res = MockHttpServletResponse()
            RequestLogFilter().doFilter(req, res, MockFilterChain())

            val lines = appender.list.map { it.formattedMessage }
            assertEquals(1, lines.size, "每请求一条访问日志")
            val line = lines.single()
            assertTrue(line.contains("POST"), "记录 method")
            assertTrue(line.contains("/api/repos"), "记录 path")
            assertTrue(line.contains("200"), "记录 status")
            assertTrue(line.contains("ms"), "记录 durationMs")
            assertFalse(line.contains("secret-token-123"), "不得记录 token")
            assertFalse(line.contains("Bearer"), "不得记录 Authorization")
            assertFalse(line.contains("token="), "不得记录 query string / 请求体")
        } finally {
            logger.detachAppender(appender)
        }
    }
}
