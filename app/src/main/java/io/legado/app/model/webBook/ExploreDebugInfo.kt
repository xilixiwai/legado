package io.legado.app.model.webBook

/**
 * 发现页一次实际请求与解析过程的调试快照。
 *
 * 这里的 URL、请求头、响应正文均应在构造前完成脱敏和截断,
 * 不保存完整 Cookie / Authorization / 令牌等敏感数据。
 */
data class ExploreDebugInfo(
    val sourceName: String = "",
    val sourceUrl: String = "",
    val exploreName: String = "",
    val page: Int = 1,
    val ruleUrl: String = "",
    val requestUrl: String = "",
    val finalUrl: String = "",
    val method: String = "GET",
    val requestMode: String = "HTTP",
    val statusCode: Int = 0,
    val statusMessage: String = "",
    val requestElapsedMs: Long = 0L,
    val parseElapsedMs: Long = 0L,
    val redirected: Boolean = false,
    val requestHeaders: List<Pair<String, String>> = emptyList(),
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val cookieSent: Boolean = false,
    val cookieJarEnabled: Boolean = false,
    val setCookieReceived: Boolean = false,
    val contentType: String = "",
    val body: String? = null,
    val bodyLength: Int = 0,
    val bodyTruncated: Boolean = false,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val ruleUsed: String = "",
    val collectionCount: Int = -1,
    val resultCount: Int = 0,
    val resultPreview: String = "",
)

/**
 * 发现规则解析阶段的统计,由 [BookList.analyzeBookList] 回传。
 */
data class ExploreParseStats(
    val rule: String,
    val collectionCount: Int,
    val resultCount: Int,
)
