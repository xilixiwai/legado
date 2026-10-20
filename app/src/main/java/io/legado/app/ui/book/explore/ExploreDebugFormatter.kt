package io.legado.app.ui.book.explore

import io.legado.app.model.webBook.ExploreDebugInfo
import io.legado.app.utils.ExploreDebugRedactor

/**
 * 把一次发现请求的调试快照格式化为可复制文本。
 *
 * 输入数据已由 ExploreDebugRedactor 脱敏/截断;这里只负责展示结构,
 * 不重新拼接原始 Cookie 或完整响应。
 */
object ExploreDebugFormatter {

    fun format(info: ExploreDebugInfo): String = buildString {
        appendLine("【发现页响应调试】")
        appendLine("提示: 已对 Cookie / Authorization / 令牌等敏感字段做基础脱敏。")
        appendLine("响应正文仍可能包含站点数据,请勿公开分享。")
        appendLine()
        appendLine("【请求信息】")
        appendLine("发现页: ${info.exploreName.ifBlank { "(未命名)" }}")
        appendLine("书源: ${info.sourceName.ifBlank { "(未知)" }}")
        appendLine("书源 URL: ${info.sourceUrl.ifBlank { "(空)" }}")
        appendLine("规则 URL: ${info.ruleUrl.ifBlank { "(空)" }}")
        appendLine("页码: ${info.page}")
        appendLine("请求方式: ${info.method} / ${info.requestMode}")
        appendLine("实际请求 URL: ${info.requestUrl.ifBlank { "(空)" }}")
        appendLine("最终响应 URL: ${info.finalUrl.ifBlank { "(未知)" }}")
        appendLine("是否重定向: ${if (info.redirected) "是" else "否"}")
        appendLine("HTTP 状态: ${statusText(info)}")
        appendLine("请求耗时: ${info.requestElapsedMs} ms")
        appendLine("解析耗时: ${info.parseElapsedMs} ms")
        if (info.requestMode == "WebView") {
            appendLine("说明: WebView 模式的状态码可能为客户端合成值,以正文和最终 URL 为准。")
        }
        appendLine("Cookie: " + when {
            info.cookieSent -> "已携带(值已隐藏)"
            info.cookieJarEnabled -> "由 CookieJar 管理(无法确认本次是否带出)"
            else -> "未携带"
        })
        appendLine("Set-Cookie: " + when {
            info.setCookieReceived -> "已返回(值已隐藏)"
            info.requestMode == "WebView" -> "WebView 模式不提供该响应头"
            else -> "未返回"
        })
        appendLine("Content-Type: ${info.contentType.ifBlank { "(未提供)" }}")
        appendLine()
        appendLine("请求头:")
        appendHeaders(info.requestHeaders)
        appendLine()
        appendLine("响应头:")
        appendHeaders(info.responseHeaders)
        appendLine()

        appendLine("【网络响应】")
        if (info.errorType != null || info.errorMessage != null) {
            appendLine("异常类型: ${info.errorType ?: "Error"}")
            if (!info.errorMessage.isNullOrBlank()) {
                appendLine("异常信息: ${info.errorMessage}")
            }
        } else if (info.statusCode > 0 && info.statusCode !in 200..299) {
            appendLine("HTTP 请求未成功,请结合正文判断是登录页、验证页还是错误页。")
        }
        appendLine("响应正文长度: ${info.bodyLength} 字符")
        if (info.bodyTruncated) {
            appendLine(
                "正文已截断: 超过 ${ExploreDebugRedactor.BODY_PREVIEW_LIMIT} 字符," +
                    "仅展示/复制前 ${ExploreDebugRedactor.BODY_PREVIEW_LIMIT} 字符。"
            )
        }
        appendLine("响应正文:")
        val body = info.body
        if (body.isNullOrEmpty()) {
            appendLine("(空响应)")
        } else {
            appendLine(body)
        }
        appendLine()

        appendLine("【发现规则解析】")
        val rule = info.ruleUsed
        appendLine(
            "使用规则: " + if (rule.length > 2_000) {
                rule.take(2_000) + "…(已截断)"
            } else {
                rule.ifBlank { "(未获取)" }
            }
        )
        appendLine(
            "匹配元素数: " +
                if (info.collectionCount >= 0) info.collectionCount.toString() else "未知"
        )
        appendLine("解析结果数: ${info.resultCount}")
        if (info.resultCount == 0) {
            appendLine("结果为空: 可能是规则未匹配、结果被过滤,或页面本身没有数据。")
        }
        appendLine("结果预览:")
        appendLine(info.resultPreview.ifBlank { "(无)" })
    }

    private fun StringBuilder.appendHeaders(headers: List<Pair<String, String>>) {
        if (headers.isEmpty()) {
            appendLine("  (无)")
            return
        }
        headers.forEach { (name, value) ->
            appendLine("  $name: $value")
        }
    }

    private fun statusText(info: ExploreDebugInfo): String {
        if (info.statusCode <= 0) return "未获取到 HTTP 状态"
        val message = info.statusMessage.trim()
        val prefix = if (message.isEmpty()) "${info.statusCode}" else "${info.statusCode} $message"
        return if (info.statusCode in 200..299) "$prefix (成功)" else "$prefix (失败)"
    }
}
