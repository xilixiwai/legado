package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现页响应调试的脱敏/截断纯函数测试。
 * 不依赖 Android,覆盖 URL、请求头、正文和超大响应。
 */
class ExploreDebugRedactorTest {

    @Test
    fun `url keeps query keys but masks sensitive values`() {
        val url = "https://user:pass@example.com/path?q=book&token=abc&sign=xyz&page=2#frag"
        val redacted = ExploreDebugRedactor.redactUrl(url)

        assertTrue(redacted.contains("q=book"))
        assertTrue(redacted.contains("page=2"))
        assertTrue(redacted.contains("token=${ExploreDebugRedactor.MASK}"))
        assertTrue(redacted.contains("sign=${ExploreDebugRedactor.MASK}"))
        assertFalse(redacted.contains("abc"))
        assertFalse(redacted.contains("xyz"))
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("#frag"))
    }

    @Test
    fun `sensitive and unknown headers are masked while content type is kept`() {
        val headers = listOf(
            "Cookie" to "session=secret",
            "Authorization" to "Bearer secret",
            "Content-Type" to "text/html; charset=utf-8",
            "X-Custom-Token" to "secret",
            "User-Agent" to "test-agent",
            "Referer" to "https://example.com/?token=secret&q=book",
        )
        val redacted = ExploreDebugRedactor.redactHeaders(headers).toMap()

        assertEquals(ExploreDebugRedactor.MASK, redacted["Cookie"])
        assertEquals(ExploreDebugRedactor.MASK, redacted["Authorization"])
        assertEquals(ExploreDebugRedactor.MASK, redacted["X-Custom-Token"])
        assertEquals("text/html; charset=utf-8", redacted["Content-Type"])
        assertTrue(redacted["Referer"]!!.contains("q=book"))
        assertFalse(redacted["Referer"]!!.contains("secret"))
    }

    @Test
    fun `body masks common json and header credential forms`() {
        val body = """
            {"token":"json-secret","accessToken":"camel-secret","userName":"reader","password":"pass-secret"}
            Cookie: session=cookie-secret
            Authorization: Bearer bearer-secret
            <input type="password" name="password" value="html-secret">
            contact: user@example.com
        """.trimIndent()

        val redacted = ExploreDebugRedactor.redactText(body)

        assertFalse(redacted.contains("json-secret"))
        assertFalse(redacted.contains("camel-secret"))
        assertFalse(redacted.contains("pass-secret"))
        assertFalse(redacted.contains("cookie-secret"))
        assertFalse(redacted.contains("bearer-secret"))
        assertFalse(redacted.contains("html-secret"))
        assertFalse(redacted.contains("user@example.com"))
        assertTrue(redacted.contains("reader"))
    }

    @Test
    fun `large body is truncated with original length recorded`() {
        val body = "a".repeat(ExploreDebugRedactor.BODY_PREVIEW_LIMIT + 500)
        val preview = ExploreDebugRedactor.previewBody(body)

        assertEquals(ExploreDebugRedactor.BODY_PREVIEW_LIMIT, preview.text.length)
        assertEquals(body.length, preview.originalLength)
        assertTrue(preview.truncated)
    }

    @Test
    fun `empty body is not reported as truncated`() {
        val preview = ExploreDebugRedactor.previewBody("")

        assertEquals("", preview.text)
        assertEquals(0, preview.originalLength)
        assertFalse(preview.truncated)
    }

    @Test
    fun `data uri is not exposed`() {
        val redacted = ExploreDebugRedactor.redactUrl("data:text/html;base64,c2VjcmV0")
        assertEquals("data:${ExploreDebugRedactor.MASK}", redacted)
    }
}
