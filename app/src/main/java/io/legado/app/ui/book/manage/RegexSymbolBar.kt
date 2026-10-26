package io.legado.app.ui.book.manage

/**
 * 「批量替换域名」旧域名输入框下方的正则快捷符号栏：符号清单与「插入到光标位置」的纯逻辑。
 *
 * 插入行为沿用书源编辑页与替换规则编辑页 `sendText()` 的既有约定：
 * - 光标在末尾、输入框为空或没有有效光标（从未聚焦）→ 追加到末尾；
 * - 有选区（含反向选择）→ 整段替换选区内容；
 * - 其余情况 → 插入到光标处。
 *
 * 抽成不依赖 android 类的纯函数，是为了在 JVM 单元测试里覆盖光标与选区各分支；
 * 焦点保持由 UI 层保证：符号键不设为可聚焦，点击不会从输入框抢走焦点。
 */
object RegexSymbolBar {

    /**
     * 常用正则符号。`\d` 作为两字符整体插入，而不是单独的 `d`——
     * 单独插入字母会直接破坏正在编辑的表达式。
     */
    val symbols: List<String> = listOf(
        "\\", ".", "*", "+", "?", "^", "$", "(", ")", "[", "]", "{", "}", "|", "\\d"
    )

    /**
     * 计算 [symbol] 插入后的新文本与新光标位置。
     *
     * [selectionStart] / [selectionEnd] 为编辑框当前选区；返回 (新文本, 新光标位置)，
     * 光标位于刚插入内容的右侧，用户可以继续输入，输入框不会失焦、内容不会被清空。
     */
    fun insert(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        symbol: String,
    ): Pair<String, Int> {
        val sb = StringBuilder(text)
        val cursor: Int
        if (selectionStart < 0 || selectionStart >= text.length) {
            sb.append(symbol)
            cursor = sb.length
        } else if (selectionStart > selectionEnd) {
            val from = selectionEnd.coerceIn(0, text.length)
            sb.replace(from, selectionStart, symbol)
            cursor = from + symbol.length
        } else {
            val to = selectionEnd.coerceIn(selectionStart, text.length)
            sb.replace(selectionStart, to, symbol)
            cursor = selectionStart + symbol.length
        }
        return sb.toString() to cursor
    }
}
