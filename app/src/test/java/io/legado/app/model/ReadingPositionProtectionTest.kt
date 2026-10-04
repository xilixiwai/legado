package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 阅读位置保护回归测试:
 * 1. 正文刷新前后当前位置保持
 * 2. 正文前面增加内容后,当前位置仍对应原来的阅读内容
 * 3. 正文前面删除内容后,当前位置仍正确
 * 4. 目录刷新过程不能把新位置恢复成旧位置
 * 5. 阅读到新位置后重新打开,必须恢复最新位置
 * 6. App 完全重启后仍恢复最新位置(依赖 saveRead 落盘,内存拦截见 5)
 */
class ReadingPositionProtectionTest {

    // ---------- 锚点重定位(resolveAnchorPos) ----------

    @Test
    fun `refresh with unchanged content keeps position`() {
        val content = "第一章 标题\n这是正文的第一段。\n这是正文的第二段,包含足够多的文字用于分页与定位测试。"
        val pos = content.indexOf("第二段")
        assertEquals(pos, resolveAnchorPos(content, content.drop(pos).take(64), pos))
    }

    @Test
    fun `content inserted before keeps reading at same text`() {
        val pageText = "当前页起始处的独特句子,用于锚定"
        val old = "旧的前言内容\n$pageText 及后续正文"
        val oldPos = old.indexOf(pageText)
        val anchor = old.drop(oldPos).take(64)
        val new = "新增的序章内容\n新增的第二段文字\n$old 及后续正文"
        assertEquals(new.indexOf(pageText), resolveAnchorPos(new, anchor, oldPos))
        assertTrue(new.indexOf(pageText) > oldPos)
    }

    @Test
    fun `content removed before keeps reading at same text`() {
        val pageText = "当前页起始处的独特句子,用于锚定"
        val old = "很长的一段旧前言,将会被源站删除\n$pageText 及后续正文"
        val oldPos = old.indexOf(pageText)
        val anchor = old.drop(oldPos).take(64)
        val new = "$pageText 及后续正文"
        assertEquals(new.indexOf(pageText), resolveAnchorPos(new, anchor, oldPos))
        assertTrue(new.indexOf(pageText) < oldPos)
    }

    @Test
    fun `repeated anchor text picks occurrence nearest old position`() {
        val repeated = "重复片段"
        val content = "$repeated 开头\n中间内容 $repeated 结尾内容 $repeated"
        val oldPos = content.lastIndexOf(repeated)
        assertEquals(oldPos, resolveAnchorPos(content, repeated, oldPos))
    }

    @Test
    fun `missing anchor returns -1 and caller keeps position`() {
        assertEquals(-1, resolveAnchorPos("全新的正文内容", "不存在于新正文的锚点文本", 3))
        assertEquals(-1, resolveAnchorPos("", "锚点", 0))
        assertEquals(-1, resolveAnchorPos("正文", "", 0))
    }

    // ---------- 旧进度拦截(isStaleProgress) ----------

    @Test
    fun `stale snapshot from toc update is rejected`() {
        // 目录更新流程持有的快照(durChapterTime=1000)早于内存最新落盘(2000),必须拦截
        assertTrue(isStaleProgress("bookUrl", 1000L, "bookUrl", 2000L))
    }

    @Test
    fun `aliased book or fresh process is not treated as stale`() {
        // saveRead 的别名对象:落盘时间一致,不拦截
        assertFalse(isStaleProgress("bookUrl", 2000L, "bookUrl", 2000L))
        // 进程冷启动(App 重启场景 6):内存尚无已保存进度,应正常从数据库恢复
        assertFalse(isStaleProgress("bookUrl", 1000L, "bookUrl", 0L))
        // 不同书籍之间不拦截
        assertFalse(isStaleProgress("bookUrlA", 1000L, "bookUrlB", 2000L))
        // 快照缺少 bookUrl 时不做判断
        assertFalse(isStaleProgress(null, 1000L, "bookUrl", 2000L))
        assertFalse(isStaleProgress("bookUrl", 1000L, null, 2000L))
    }

    // ---------- 调用链固定(防止回归,做法参照上游 ReadBookRefreshPositionTest) ----------

    @Test
    fun `reader refresh preserves position before discarding layout`() {
        val refresh = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookActivity.kt")
            .substringAfter("R.id.menu_refresh,")
            .substringBefore("R.id.menu_refresh_after")
        assertOrder(
            refresh,
            "ReadBook.preserveCurrentPositionForRefresh()",
            "ReadBook.curTextChapter = null",
            "viewModel.refreshContentDur(it)"
        )
    }

    @Test
    fun `upData rejects stale progress before restoring`() {
        val upData = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
            .substringAfter("fun upData(book: Book)")
            .substringBefore("fun upWebBook")
        assertOrder(
            upData,
            "val oldBook = ReadBook.book",
            "isStaleProgress(",
            "if (durChapterIndex != book.durChapterIndex)"
        )
        assertTrue(upData.contains("book.durChapterIndex = durChapterIndex"))
        assertTrue(upData.contains("clearTextChapter()"))
    }

    @Test
    fun `chapter list update copies newer memory progress into snapshot`() {
        val onTocUpdated = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
            .substringAfter("fun onChapterListUpdated(")
            .substringBefore("private fun clearExpiredChapterLoadingJob")
        assertOrder(
            onTocUpdated,
            "val oldBook = book",
            "book = newBook",
            "isStaleProgress(",
            "newBook.durChapterIndex = durChapterIndex"
        )
    }

    @Test
    fun `content reload resolves anchor before final upContent`() {
        val offsetZero = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
            .substringAfter("0 -> curChapterLoadingLock.withLock {")
            .substringBefore("-1 -> prevChapterLoadingLock")
        assertOrder(
            offsetZero,
            "curTextChapter = textChapter",
            "resolvePendingPositionAnchor(book, textChapter)",
            "callBack?.upContent(offset, !available && resetPageOffset)"
        )
    }

    private fun assertOrder(source: String, vararg expected: String) {
        var position = -1
        expected.forEach { text ->
            val next = source.indexOf(text)
            assertTrue("Missing or out of order: $text", next > position)
            position = next
        }
    }

    private fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }
}
