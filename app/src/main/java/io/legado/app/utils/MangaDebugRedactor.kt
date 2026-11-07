package io.legado.app.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 漫画响应的有限脱敏。复用发现页的键/头策略，不改变发现页的输出和正文限制。 */
object MangaDebugRedactor {
    private const val MASK = ExploreDebugRedactor.MASK
    private val pair = Regex("""(?<![a-zA-Z0-9_-])(["']?)([a-zA-Z][a-zA-Z0-9_-]{0,127})\1\s*[:=]\s*""")
    private val urls = Regex("""(?i)https?://[^\s<>"']+""")
    private val bearer = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val headerLine = Regex("(?im)^[\\t ]*(?:cookie|set-cookie|authorization|proxy-authorization)\\s*:[^\\r\\n]*")
    private val credentialHeaders = setOf("cookie", "set-cookie", "authorization", "proxy-authorization")

    private fun sensitive(key: String) = ExploreDebugRedactor.isSensitiveKey(key) ||
        key.equals("verify", true) || key.equals("csrf", true)

    fun url(value: String): String {
        if (value.startsWith("data:", true)) return "data:***"
        if (value.length > 16_384) return "(地址过长，已隐藏)"
        val address = value.substringBefore(optionMarker.find(value)?.value ?: "\u0000")
        val parsed = address.toHttpUrlOrNull() ?: return "(非 HTTP 地址或 URL 规则，内容已隐藏)"
        val builder = parsed.newBuilder()
        parsed.queryParameterNames.filter(::sensitive).forEach { builder.setQueryParameter(it, MASK) }
        return ExploreDebugRedactor.redactUrl(builder.build().toString()) +
            if (address.length < value.length) " (URL 规则选项未展示)" else ""
    }

    private val optionMarker = Regex(",\\s*\\{")
    private val htmlInput = Regex("(?i)<input\\b[^>]*(?:>|$)")
    private val attribute = Regex("""([a-zA-Z_-]+)\s*=\s*(["'])(.*?)\2""")

    fun headers(values: Iterable<Pair<String, String>>): String {
        val bounded = values.take(61)
        val result = bounded.take(60).joinToString("\n") { (rawName, rawValue) ->
            val name = rawName.take(128)
            val value = rawValue.take(2_048)
            val safe = if (name.lowercase() in setOf("location", "referer", "origin")) {
                url(value)
            } else text(ExploreDebugRedactor.redactHeaders(listOf(name to value)).single().second)
            "${text(name)}: $safe"
        }
        return result.take(8_000) + if (result.length > 8_000 || bounded.size > 60) "\n(响应头已截断)" else ""
    }

    /** 线性扫描敏感值（包括嵌套对象/数组），避免在长文本上使用发现页的回溯表达式。 */
    fun text(value: String, limit: Int = 32_000): String {
        val input = value.take(65_536)
        val output = StringBuilder()
        var copied = 0
        for (match in pair.findAll(input)) {
            if (match.range.first < copied || !sensitive(match.groupValues[2])) continue
            val start = match.range.last + 1
            var end = valueEnd(input, start)
            if (match.groupValues[2].lowercase() in credentialHeaders &&
                start < input.length && input[start] !in "\"'{[") {
                // 未加引号的 HTTP 凭据可能包含空格、分号或多个字段，不只遮第一个词。
                end = input.indexOfAny(charArrayOf('\r', '\n'), start).takeIf { it >= 0 } ?: input.length
            } else if (input.substring(start, end).equals("Bearer", true) ||
                input.substring(start, end).equals("Basic", true)) {
                while (end < input.length && input[end] == ' ') end++
                end = valueEnd(input, end)
            }
            output.append(input, copied, start).append(MASK)
            copied = end
        }
        output.append(input, copied, input.length)
        var result = headerLine.replace(output.toString()) { it.value.substringBefore(':') + ": $MASK" }
        result = urls.replace(result) { url(it.value) }
        result = bearer.replace(result, MASK)
        result = jwt.replace(result, MASK)
        result = htmlInput.replace(result) { tag ->
            val attributes = attribute.findAll(tag.value).toList()
            if (attributes.any { it.groupValues[1].lowercase() in setOf("name", "id") && sensitive(it.groupValues[3]) }) {
                attribute.replace(tag.value) { attr ->
                    if (attr.groupValues[1].equals("value", true)) "value=\"$MASK\"" else attr.value
                }
            } else tag.value
        }
        return result.take(limit)
    }

    private fun valueEnd(text: String, start: Int): Int {
        if (start >= text.length) return start
        val first = text[start]
        var quote: Char? = null
        var depth = 0
        var escaped = false
        val structured = first == '{' || first == '['
        val quoted = first == '"' || first == '\''
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            if (quote != null) {
                if (c == '\\') escaped = true
                else if (c == quote) {
                    quote = null
                    if (quoted && depth == 0) return i + 1
                }
            } else when {
                c == '"' || c == '\'' -> quote = c
                structured && (c == '{' || c == '[') -> depth++
                structured && (c == '}' || c == ']') -> if (--depth == 0) return i + 1
                !structured && !quoted && (c.isWhitespace() || c in "<>&,}") -> return i
            }
        }
        return text.length
    }
}
