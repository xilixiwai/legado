package io.legado.app.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 发现页响应调试的脱敏与截断工具(纯函数)。
 *
 * 安全边界:
 * - 请求/响应头中的 Cookie、Authorization、令牌类字段不展示原值;
 * - URL 查询参数中的 token/sign/password/session 等值替换为 ***;
 * - 响应正文做基础正则脱敏,但仍可能包含站点信息,界面需提示不要公开分享。
 */
object ExploreDebugRedactor {

    const val MASK = "***"
    const val BODY_PREVIEW_LIMIT = 120_000
    private const val HEADER_LIMIT = 60
    private const val URL_DISPLAY_LIMIT = 2_000

    data class Preview(
        val text: String,
        val originalLength: Int,
        val truncated: Boolean,
    )

    private val sensitiveKeyPattern = Regex(
        "(token|secret|password|passwd|pwd|cookie|session|authorization|" +
            "signature|ticket|api[_-]?key|access[_-]?key|private[_-]?key|credential)",
        RegexOption.IGNORE_CASE
    )
    private val exactSensitiveKeys = setOf("sign", "sid", "auth")

    private val allowedHeaders = setOf(
        "user-agent", "accept", "accept-language", "accept-encoding",
        "content-type", "content-length", "referer", "origin", "host",
        "connection", "cache-control", "x-requested-with", "location",
        "content-encoding", "server", "date", "vary"
    )
    private val urlHeaders = setOf("referer", "origin", "location")

    private val keyValuePattern = Regex(
        "(?i)((?:[a-z0-9_-]*(?:token|secret|password|passwd|pwd|cookie|" +
            "session|signature|ticket|credential|api[_-]?key)[a-z0-9_-]*|sign|auth|" +
            "authorization|csrf)" +
            "\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s<>&,}]+)"
    )
    private val headerLinePattern = Regex(
        "(?im)^(cookie|set-cookie|authorization|proxy-authorization)\\s*:\\s*.*$"
    )
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val jwtPattern = Regex(
        "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
    )
    private val emailPattern = Regex(
        "(?i)\\b[A-Z0-9._%+-]+(@[A-Z0-9.-]+\\.[A-Z]{2,})\\b"
    )
    private val htmlPasswordPattern = Regex(
        "(?i)(<(?:input|textarea)[^>]*?(?:name|id)\\s*=\\s*[\"']?" +
            "(?:password|passwd|pwd|token|csrf[_-]?token)[\"']?[^>]*?value\\s*=\\s*)([\"'])(.*?)\\2"
    )

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower in exactSensitiveKeys || sensitiveKeyPattern.containsMatchIn(lower)
    }

    fun isSensitiveHeader(name: String): Boolean = isSensitiveKey(name)

    /**
     * 保留 URL 的 scheme/host/path/query 结构,只替换敏感查询参数值。
     * 无法解析为 HTTP URL 时退化为去掉 query/fragment 的诊断 URL,避免泄漏 data URI 内容。
     */
    fun redactUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        if (url.startsWith("data:", true)) return "data:$MASK"
        val parsed = url.toHttpUrlOrNull() ?: return DiagnosticUtils.sanitizeUrl(url)
        val builder = parsed.newBuilder().fragment(null)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            builder.username("redacted").password("redacted")
        }
        parsed.queryParameterNames.forEach { name ->
            if (isSensitiveKey(name)) {
                builder.setQueryParameter(name, MASK)
            }
        }
        val result = builder.build().toString()
        return if (result.length > URL_DISPLAY_LIMIT) {
            result.take(URL_DISPLAY_LIMIT) + "…(已截断)"
        } else {
            result
        }
    }

    /**
     * 只展示必要的请求/响应头。敏感头与未知头只保留头名,值替换为 ***。
     */
    fun redactHeaders(headers: Iterable<Pair<String, String>>): List<Pair<String, String>> {
        val source = headers.toList()
        val result = source.take(HEADER_LIMIT).map { (name, value) ->
            val lower = name.lowercase()
            when {
                isSensitiveHeader(name) -> name to MASK
                lower in urlHeaders -> name to redactUrl(value)
                lower in allowedHeaders -> name to value
                else -> name to MASK
            }
        }.toMutableList()
        if (source.size > HEADER_LIMIT) {
            result.add("(省略)" to "共 ${source.size} 项,仅展示前 $HEADER_LIMIT 项")
        }
        return result
    }

    /**
     * 对响应正文做基础脱敏。不是完整 DLP,仅覆盖常见 JSON/表单/请求头/令牌形态。
     */
    fun redactText(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var result = keyValuePattern.replace(text) { match ->
            match.groupValues[1] + MASK
        }
        result = headerLinePattern.replace(result) { match ->
            match.groupValues[1] + ": " + MASK
        }
        result = bearerPattern.replace(result, "Bearer $MASK")
        result = jwtPattern.replace(result, MASK)
        result = emailPattern.replace(result) { match ->
            MASK + match.groupValues[1]
        }
        result = htmlPasswordPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + MASK + match.groupValues[2]
        }
        return result
    }

    /**
     * 截断后再脱敏,避免对超大页面做无界处理;originalLength 始终是原始字符数。
     */
    fun previewBody(body: String?): Preview {
        if (body.isNullOrEmpty()) {
            return Preview("", body?.length ?: 0, false)
        }
        val truncated = body.length > BODY_PREVIEW_LIMIT
        // 多截一点再脱敏,避免截断点正好切在令牌中间导致脱敏漏值。
        val captured = if (truncated) {
            body.take(BODY_PREVIEW_LIMIT + 256)
        } else {
            body
        }
        val redacted = redactText(captured)
        return Preview(
            text = redacted.take(BODY_PREVIEW_LIMIT),
            originalLength = body.length,
            truncated = truncated || redacted.length > BODY_PREVIEW_LIMIT,
        )
    }
}
