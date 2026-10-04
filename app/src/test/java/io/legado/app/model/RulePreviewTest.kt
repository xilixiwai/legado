package io.legado.app.model

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * RulePreview(书源规则预览)回归测试。
 * 预览必须复用现有 AnalyzeRule 引擎;测试同时验证与正式链一致的执行顺序。
 */
class RulePreviewTest {

    private val html = """
        <html><body>
            <div class="content">
                <p>第一段正文</p>
                <p>第二段正文</p>
            </div>
            <div class="other">不相关内容</div>
        </body></html>
    """.trimIndent()

    private fun source(contentRule: String?, replaceRegex: String? = null): BookSource =
        BookSource().apply {
            bookSourceUrl = "https://example.com"
            bookSourceName = "测试源"
            getContentRule().content = contentRule
            getContentRule().replaceRegex = replaceRegex
        }

    //基础:CSS 规则提取本地 HTML 正文
    @Test
    fun `css rule extracts local html`() = runBlocking {
        val result = RulePreview.previewContent(source(".content@text"), html)
        assertTrue(result.contains("第一段正文"))
        assertTrue(result.contains("第二段正文"))
        assertFalse(result.contains("不相关内容"))
    }

    //§18 一致性:预览 == 直接用正式链同序调用 AnalyzeRule
    @Test
    fun `preview equals direct engine execution in bookContent order`() = runBlocking {
        val src = source(".content@text")
        val direct = run {
            val analyzeRule = io.legado.app.model.analyzeRule.AnalyzeRule(source = src)
            analyzeRule.setContent(html)
            analyzeRule.setCoroutineContext(coroutineContext)
            var content =
                analyzeRule.getString(src.getContentRule().content, unescape = false)
            content = io.legado.app.utils.HtmlFormatter.formatKeepImg(content, null)
            if (content.indexOf('&') > -1) {
                content = org.apache.commons.text.StringEscapeUtils.unescapeHtml4(content)
            }
            content
        }
        assertEquals(direct, RulePreview.previewContent(src, html))
    }

    //§17:书源级 replaceRegex 与正式链一致(格式 ##正则##替换,提取后替换,含正式链的行首缩进)
    @Test
    fun `source replaceRegex applied after extraction`() = runBlocking {
        val result = RulePreview.previewContent(
            source(".content@text", replaceRegex = "##第一段正文##已替换内容"),
            html
        )
        assertTrue(result.contains("已替换内容"))
        assertFalse(result.contains("第一段正文"))
    }

    //§17:replaceRegex 为正则时的替换
    @Test
    fun `source replaceRegex supports regex`() = runBlocking {
        val result = RulePreview.previewContent(
            source(".content@text", replaceRegex = "##第二段##尾段"),
            html
        )
        assertTrue(result.contains("第一段正文"))
        assertTrue(result.contains("尾段正文"))
    }

    //§15:空测试数据 → 空结果,不崩溃
    @Test
    fun `empty html returns blank`() = runBlocking {
        val result = RulePreview.previewContent(source(".content@text"), "")
        assertTrue(result.isBlank())
    }

    //§15:空正文规则 → 空结果,不崩溃(以引擎语义为准)
    @Test
    fun `blank content rule returns blank`(): Unit = runBlocking {
        var blank = false
        try {
            blank = RulePreview.previewContent(source(""), html).isBlank()
        } catch (_: Exception) {
            //引擎将空规则视为异常同样可接受(ViewModel 展示为错误状态)
            blank = true
        }
        assertTrue(blank)
    }

    //§15:无目标元素 → 空结果,不崩溃
    @Test
    fun `missing target element returns blank`() = runBlocking {
        val result = RulePreview.previewContent(
            source(".not-exists@text"),
            html
        )
        assertTrue(result.isBlank())
    }

    //§15:非法规则 → 引擎抛出 Error/Exception(由 ViewModel 转为错误状态),不得返回脏数据
    @Test
    fun `invalid rule throws instead of crashing silently`(): Unit = runBlocking {
        var threw = false
        try {
            RulePreview.previewContent(source(".content[href@text"), html)
        } catch (e: Throwable) {
            threw = true
        }
        assertTrue("invalid rule should throw", threw)
    }
}
