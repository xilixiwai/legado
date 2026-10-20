package io.legado.app.ui.book.explore

import io.legado.app.model.webBook.ExploreDebugInfo
import io.legado.app.utils.ExploreDebugRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现页响应调试文本格式化测试:请求、HTTP、正文和解析阶段必须可区分。
 */
class ExploreDebugFormatterTest {

    private fun info(
        body: String? = "<html><body><div class=\"book\">Book A</div></body></html>",
        statusCode: Int = 200,
        errorType: String? = null,
        errorMessage: String? = null,
        truncated: Boolean = false,
    ) = ExploreDebugInfo(
        sourceName = "测试书源",
        sourceUrl = "https://example.com",
        exploreName = "最近更新",
        page = 1,
        ruleUrl = "https://example.com/list/{{page}}.html",
        requestUrl = "https://example.com/list/1.html",
        finalUrl = "https://example.com/list/1.html",
        method = "GET",
        requestMode = "HTTP",
        statusCode = statusCode,
        statusMessage = "OK",
        requestElapsedMs = 123,
        parseElapsedMs = 45,
        requestHeaders = listOf("User-Agent" to "ua", "Cookie" to ExploreDebugRedactor.MASK),
        responseHeaders = listOf(
            "Content-Type" to "text/html",
            "Set-Cookie" to ExploreDebugRedactor.MASK
        ),
        cookieSent = true,
        setCookieReceived = true,
        contentType = "text/html",
        body = body,
        bodyLength = body?.length ?: 0,
        bodyTruncated = truncated,
        errorType = errorType,
        errorMessage = errorMessage,
        ruleUsed = "div.book",
        collectionCount = 10,
        resultCount = 8,
        resultPreview = "Book A / Author A"
    )

    @Test
    fun `formats request response and parse stages`() {
        val text = ExploreDebugFormatter.format(info())

        assertTrue(text.contains("【请求信息】"))
        assertTrue(text.contains("实际请求 URL: https://example.com/list/1.html"))
        assertTrue(text.contains("HTTP 状态: 200 OK (成功)"))
        assertTrue(text.contains("Cookie: 已携带"))
        assertTrue(text.contains("【网络响应】"))
        assertTrue(text.contains("Book A"))
        assertTrue(text.contains("【发现规则解析】"))
        assertTrue(text.contains("使用规则: div.book"))
        assertTrue(text.contains("匹配元素数: 10"))
        assertTrue(text.contains("解析结果数: 8"))
    }

    @Test
    fun `json response body is displayed as-is`() {
        val json = """{"books":[{"name":"Book A","author":"Author A"}]}"""
        val text = ExploreDebugFormatter.format(info(body = json))

        assertTrue(text.contains(json))
        assertTrue(text.contains("Content-Type: text/html"))
    }

    @Test
    fun `non successful http status is called out`() {
        val text = ExploreDebugFormatter.format(
            info(statusCode = 500).copy(statusMessage = "Server Error")
        )

        assertTrue(text.contains("500 Server Error (失败)"))
        assertTrue(text.contains("HTTP 请求未成功"))
    }

    @Test
    fun `cookie jar state is shown when cookie header is absent`() {
        val text = ExploreDebugFormatter.format(
            info().copy(cookieSent = false, cookieJarEnabled = true)
        )

        assertTrue(text.contains("由 CookieJar 管理"))
    }

    @Test
    fun `empty body is explicit and zero results are explained`() {
        val text = ExploreDebugFormatter.format(
            info(body = "", statusCode = 200).copy(resultCount = 0, resultPreview = "")
        )

        assertTrue(text.contains("(空响应)"))
        assertTrue(text.contains("结果为空"))
        assertTrue(text.contains("结果预览:\n(无)"))
    }

    @Test
    fun `error and truncation are explicit`() {
        val text = ExploreDebugFormatter.format(
            info(
                body = null,
                statusCode = 0,
                errorType = "SocketTimeoutException",
                errorMessage = "timeout",
                truncated = true,
            )
        )

        assertTrue(text.contains("异常类型: SocketTimeoutException"))
        assertTrue(text.contains("异常信息: timeout"))
        assertTrue(text.contains("正文已截断"))
        assertTrue(text.contains("未获取到 HTTP 状态"))
    }

    @Test
    fun `formatted output does not contain raw cookie or token`() {
        val text = ExploreDebugFormatter.format(info())

        assertFalse(text.contains("session=secret"))
        assertFalse(text.contains("Bearer secret"))
    }
}
