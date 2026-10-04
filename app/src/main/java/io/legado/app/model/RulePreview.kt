package io.legado.app.model

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.utils.HtmlFormatter
import org.apache.commons.text.StringEscapeUtils
import kotlin.coroutines.coroutineContext

/**
 * 书源规则预览:对现有 AnalyzeRule 引擎的薄封装,不是第二套解析器。
 *
 * 执行顺序与正式正文链 BookContent.analyzeContent 一致:
 * 正文规则 → HtmlFormatter → html 反转义 → 书源级 replaceRegex。
 *
 * 不写数据库、不发起主动网络请求;仅当规则本身包含 JavaScript 时,
 * 才会与正式阅读一样使用书源的规则执行环境(可能访问 cookie/缓存/网络)。
 */
object RulePreview {

    /**
     * 用书源当前(可以是未保存)的正文规则解析本地测试 HTML。
     * @param html 用户提供的本地测试数据,不发网络请求获取
     * @return 正文规则执行结果;空结果返回空串,规则异常直接抛出由调用方展示
     */
    @Throws(Exception::class)
    suspend fun previewContent(
        bookSource: BookSource,
        html: String
    ): String {
        val contentRule = bookSource.getContentRule()
        val analyzeRule = AnalyzeRule(source = bookSource)
        //本地预览没有真实页面 URL,baseUrl 留空,相对链接不会被补全
        analyzeRule.setContent(html)
        analyzeRule.setCoroutineContext(coroutineContext)
        var content = analyzeRule.getString(contentRule.content, unescape = false)
        content = HtmlFormatter.formatKeepImg(content, null)
        if (content.indexOf('&') > -1) {
            content = StringEscapeUtils.unescapeHtml4(content)
        }
        //书源级全文替换,与 BookContent.analyzeContent 保持一致
        val replaceRegex = contentRule.replaceRegex
        if (!replaceRegex.isNullOrEmpty()) {
            content = content.split(AppPattern.LFRegex).joinToString("\n") { it.trim() }
            content = analyzeRule.getString(replaceRegex, content)
            content = content.split(AppPattern.LFRegex).joinToString("\n") { "　　$it" }
        }
        return content
    }
}
