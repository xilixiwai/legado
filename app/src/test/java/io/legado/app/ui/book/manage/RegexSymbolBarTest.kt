package io.legado.app.ui.book.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「批量替换域名」正则快捷符号栏的纯逻辑测试。
 *
 * UI 层的焦点保持（符号键不可聚焦、Editable 原地替换不清焦点）无法在 JVM 单测验证，
 * 由实现保证：见 [RegexSymbolBar] 的 KDoc 与 BookshelfManageActivity.insertRegexSymbol。
 */
class RegexSymbolBarTest {

    // ------------------------------------------------------------------ 光标位置插入

    @Test
    fun `光标位置插入`() {
        assertEquals("a\\dbc" to 3, RegexSymbolBar.insert("abc", 1, 1, "\\d"))
        assertEquals("a.b" to 2, RegexSymbolBar.insert("ab", 1, 1, "."))
    }

    @Test
    fun `空文本插入`() {
        assertEquals("\\" to 1, RegexSymbolBar.insert("", 0, 0, "\\"))
        assertEquals("^" to 1, RegexSymbolBar.insert("", -1, -1, "^"))
    }

    @Test
    fun `插入后光标位于新内容右侧可继续输入`() {
        // 连续插入 ^gpt\d+\.example\.com$ 模拟真实编辑过程，最终拼出完整表达式
        var text = ""
        var cursor = 0
        "^gpt\\d+\\.example\\.com$".forEachIndexed { _, c ->
            val (t, p) = RegexSymbolBar.insert(text, cursor, cursor, c.toString())
            text = t
            cursor = p
        }
        assertEquals("^gpt\\d+\\.example\\.com$", text)
        assertEquals(text.length, cursor)
    }

    // ------------------------------------------------------------------ 选中内容

    @Test
    fun `选中内容被整段替换`() {
        // 选中 bcd 后点 + ：只替换选区，前后内容保留
        assertEquals("a+e" to 2, RegexSymbolBar.insert("abcde", 1, 4, "+"))
    }

    @Test
    fun `反向选区同样整段替换`() {
        // 用户反方向拖出的选区 selectionStart > selectionEnd
        assertEquals("a+e" to 2, RegexSymbolBar.insert("abcde", 4, 1, "+"))
    }

    // ------------------------------------------------------------------ 光标边界

    @Test
    fun `光标在末尾时追加`() {
        assertEquals("abc+" to 4, RegexSymbolBar.insert("abc", 3, 3, "+"))
    }

    @Test
    fun `无有效光标时追加且不破坏已有内容`() {
        // 输入框从未聚焦时选区可能为 -1，此时追加到末尾而不是覆盖开头
        assertEquals("abc+" to 4, RegexSymbolBar.insert("abc", -1, -1, "+"))
    }

    @Test
    fun `越界选区被收敛不抛异常`() {
        // 光标越界：按「光标在末尾」追加
        assertEquals("ab\\d" to 4, RegexSymbolBar.insert("ab", 5, 5, "\\d"))
        // 选区终点越界：收敛到文本长度，替换到串尾
        assertEquals("a\\d" to 3, RegexSymbolBar.insert("ab", 1, 99, "\\d"))
    }

    // ------------------------------------------------------------------ 符号清单

    @Test
    fun `符号清单覆盖常用正则元字符`() {
        val symbols = RegexSymbolBar.symbols
        listOf("\\", ".", "*", "+", "?", "^", "$", "(", ")", "[", "]", "{", "}", "|")
            .forEach { assertTrue("缺少符号 $it", it in symbols) }
    }

    @Test
    fun `数字快捷键是两字符整体而不是单独的d`() {
        // 单独插入 d 会破坏正在编辑的表达式，必须以 \d 整体插入
        assertTrue("\\d" in RegexSymbolBar.symbols)
        assertTrue("d" !in RegexSymbolBar.symbols)
    }
}
