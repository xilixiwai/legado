package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 正文页无书源提示判定回归测试。
 * 只在网络书籍、无书源且没有任何章节数据时提示;
 * 有书源(即使请求失败)、本地书籍、已有目录/缓存正文时不提示。
 */
class ReadNoSourcePromptTest {

    @Test
    fun `network book without source and no chapters prompts`() {
        assertTrue(
            shouldShowNoSourceMsg(isLocal = false, hasSource = false, chapterSize = 0)
        )
    }

    @Test
    fun `book with source but request failure does not prompt`() {
        assertFalse(
            shouldShowNoSourceMsg(isLocal = false, hasSource = true, chapterSize = 0)
        )
    }

    @Test
    fun `local book never prompts`() {
        assertFalse(
            shouldShowNoSourceMsg(isLocal = true, hasSource = false, chapterSize = 0)
        )
    }

    @Test
    fun `book with chapters or cached content does not prompt`() {
        assertFalse(
            shouldShowNoSourceMsg(isLocal = false, hasSource = false, chapterSize = 1)
        )
        assertFalse(
            shouldShowNoSourceMsg(isLocal = false, hasSource = false, chapterSize = 100)
        )
    }

    @Test
    fun `unknown chapter size is treated as no chapters`() {
        assertTrue(
            shouldShowNoSourceMsg(isLocal = false, hasSource = false, chapterSize = -1)
        )
    }

    @Test
    fun `repeated decision is stable and does not change`() {
        repeat(3) {
            assertTrue(
                shouldShowNoSourceMsg(isLocal = false, hasSource = false, chapterSize = 0)
            )
        }
    }

    @Test
    fun `reader wiring keeps no source prompt only for missing source`() {
        val vm = source("app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt")

        val initBook = vm.substringAfter("private suspend fun initBook(book: Book)")
            .substringBefore("private fun checkLocalBookFileExist")
        assertTrue(initBook.contains("if (!book.isLocal && ReadBook.bookSource == null)"))
        assertTrue(initBook.contains("if (ReadBook.chapterSize == 0)"))
        assertTrue(initBook.contains("R.string.error_no_source"))

        val loadChapterList = vm.substringAfter("fun loadChapterList(book: Book)")
            .substringBefore("private suspend fun loadChapterListAwait")
        assertTrue(loadChapterList.contains("shouldShowNoSourceMsg("))
        assertTrue(loadChapterList.contains("R.string.error_no_source"))

        // 有书源但目录请求失败仍使用 error_load_toc,不能误报为无书源
        val loadChapterListAwait = vm.substringAfter("private suspend fun loadChapterListAwait")
            .substringBefore("fun syncBookProgress")
        assertTrue(loadChapterListAwait.contains("R.string.error_load_toc"))
        assertFalse(loadChapterListAwait.contains("R.string.error_no_source"))

        val autoChange = vm.substringAfter("private fun autoChangeSource")
            .substringBefore("fun openChapter")
        assertTrue(autoChange.contains("shouldShowNoSourceMsg("))
        assertTrue(autoChange.contains("R.string.error_no_source"))
    }

    @Test
    fun `same no source message is not re-posted`() {
        val readBook = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
        val upMsg = readBook.substringAfter("fun upMsg(msg: String?)")
            .substringBefore("fun moveToNextPage")
        assertTrue(upMsg.contains("if (ReadBook.msg != msg)"))
        assertTrue(upMsg.contains("callBack?.upContent()"))
    }

    private fun source(path: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(File(userDir)) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, path).readText().replace("\r\n", "\n")
    }
}
