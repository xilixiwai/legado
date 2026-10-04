package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/**
 * 诊断日志纯函数测试。
 *
 * 目的:锁定日志"只记录、不改行为"的两个前提
 * 1. 脱敏函数不得把 query / fragment / userinfo 里的敏感信息带进日志;
 * 2. 耗时 / 异常分类函数是纯函数,不依赖任何业务状态。
 */
class DiagnosticUtilsTest {

    @Test
    fun `sanitizeUrl strips query fragment and userinfo`() {
        assertEquals(
            "https://example.com/book/1",
            DiagnosticUtils.sanitizeUrl("https://example.com/book/1?token=secret&sign=abc#frag")
        )
        assertEquals(
            "https://example.com/book/1",
            DiagnosticUtils.sanitizeUrl("https://user:pass@example.com/book/1?t=1")
        )
        assertEquals("", DiagnosticUtils.sanitizeUrl(null))
        assertEquals("", DiagnosticUtils.sanitizeUrl(""))
        assertEquals("", DiagnosticUtils.sanitizeUrl("   "))
    }

    @Test
    fun `sanitizeUrl keeps plain url and relative path intact`() {
        assertEquals(
            "https://example.com/path",
            DiagnosticUtils.sanitizeUrl("https://example.com/path")
        )
        assertEquals("/relative/path", DiagnosticUtils.sanitizeUrl("/relative/path?x=1"))
        assertEquals("file:///sdcard/a.txt", DiagnosticUtils.sanitizeUrl("file:///sdcard/a.txt"))
    }

    @Test
    fun `sanitizeUrl drops everything after the last at sign in authority`() {
        assertEquals("http://c/", DiagnosticUtils.sanitizeUrl("http://a@b@c/?q=1"))
    }

    @Test
    fun `elapsedMs is never negative`() {
        assertEquals(120L, DiagnosticUtils.elapsedMs(1000L, 1120L))
        assertEquals(0L, DiagnosticUtils.elapsedMs(1000L, 900L))
        assertEquals(0L, DiagnosticUtils.elapsedMs(1000L, 1000L))
    }

    @Test
    fun `exceptionType returns simple name and none for null`() {
        assertEquals("none", DiagnosticUtils.exceptionType(null))
        assertEquals("IllegalStateException", DiagnosticUtils.exceptionType(IllegalStateException()))
        assertEquals("SocketTimeoutException", DiagnosticUtils.exceptionType(SocketTimeoutException()))
    }

    @Test
    fun `isTimeout detects timeout in self and cause chain`() {
        assertFalse(DiagnosticUtils.isTimeout(null))
        assertFalse(DiagnosticUtils.isTimeout(IllegalStateException("boom")))
        assertTrue(DiagnosticUtils.isTimeout(TimeoutException("t")))
        assertTrue(DiagnosticUtils.isTimeout(SocketTimeoutException("t")))
        assertTrue(DiagnosticUtils.isTimeout(InterruptedIOException("t")))
        assertTrue(
            DiagnosticUtils.isTimeout(
                IllegalStateException("wrapper", TimeoutException("inner"))
            )
        )
    }
}
