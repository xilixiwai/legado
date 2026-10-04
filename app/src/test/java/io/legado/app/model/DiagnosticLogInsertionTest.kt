package io.legado.app.model

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 诊断日志插入点回归测试。
 *
 * 本轮只允许"诊断级可观测性增强",因此必须证明两件事:
 * 1. 每处日志都已真正插入(断言存在 [诊断] 标记,避免测试空转);
 * 2. 日志插入没有改变原有判定表达式及其顺序
 *    (timeout 数值、取消语义、阻塞循环、cache 判定、useWebView 分支)。
 *
 * 做法参照既有 ReadingPositionProtectionTest 的"调用链固定"断言。
 */
class DiagnosticLogInsertionTest {

    @Test
    fun `explore keeps debug timeout and error propagation with diagnostics`() {
        val block = source("app/src/main/java/io/legado/app/ui/book/explore/ExploreShowViewModel.kt")
            .substringAfter("fun explore() {")
            .substringBefore("fun isInBookShelf(")
        assertTrue(block.contains("[诊断]发现开始"))
        assertTrue(block.contains("[诊断]发现成功"))
        assertTrue(block.contains("[诊断]发现失败"))
        assertOrder(
            block,
            ".timeout(if (BuildConfig.DEBUG) 0L else 30000L)",
            "errorLiveData.postValue(it.stackTraceStr)"
        )
    }

    @Test
    fun `loadContent keeps cache decision and download fallback`() {
        val block = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
            .substringAfter("fun loadContent(\n        index: Int,")
            .substringBefore("suspend fun loadContentAwait(")
        assertTrue(block.contains("[诊断]正文开始"))
        assertTrue(block.contains("[诊断]正文失败"))
        assertOrder(
            block,
            "val cachedContent = BookHelp.getContent(book, chapter)",
            "cachedContent?.let {",
            "contentLoadFinish(",
            "} ?: download("
        )
    }

    @Test
    fun `loadContentAwait keeps cache decision and download fallback`() {
        val block = source("app/src/main/java/io/legado/app/model/ReadBook.kt")
            .substringAfter("suspend fun loadContentAwait(")
            .substringBefore("private suspend fun downloadIndex(")
        assertTrue(block.contains("[诊断]正文成功"))
        assertTrue(block.contains("[诊断]正文失败"))
        assertOrder(
            block,
            "val cachedContent = BookHelp.getContent(book, chapter)",
            "val content = cachedContent ?: downloadAwait(chapter)",
            "contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)"
        )
    }

    @Test
    fun `change source search keeps withTimeout and ensureActive`() {
        val block = source(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        ).substringAfter("private fun search() {")
            .substringBefore("private suspend fun search(source: BookSource) {")
        assertTrue(block.contains("[诊断]换源搜索成功"))
        assertTrue(block.contains("[诊断]换源搜索失败"))
        assertOrder(
            block,
            "withTimeout(60000L) {",
            "currentCoroutineContext().ensureActive()"
        )
    }

    @Test
    fun `change source getToc keeps original result construction`() {
        val block = source(
            "app/src/main/java/io/legado/app/ui/book/changesource/ChangeBookSourceViewModel.kt"
        ).substringAfter("suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {")
            .substringBefore("fun disableSource(")
        assertTrue(block.contains("[诊断]换源目录成功"))
        assertTrue(block.contains("[诊断]换源目录失败"))
        assertOrder(
            block,
            "WebBook.getChapterListAwait(source, book).getOrThrow()",
            "Pair(toc, source)"
        )
    }

    @Test
    fun `verification help keeps synchronized blocking loop`() {
        val block = source("app/src/main/java/io/legado/app/help/source/SourceVerificationHelp.kt")
            .substringAfter("fun getVerificationResult(")
            .substringBefore("fun startBrowser(")
        assertTrue(block.contains("[诊断]源验证命中"))
        assertTrue(block.contains("[诊断]源验证返回"))
        assertOrder(
            block,
            "clearResult(source.getKey())",
            "while (getResult(source.getKey()) == null) {",
            "LockSupport.parkNanos(this, waitTime)",
            "val result = getResult(source.getKey())!!"
        )
    }

    @Test
    fun `analyze url keeps webview branch condition`() {
        val block = source("app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt")
            .substringAfter("suspend fun getStrResponseAwait(")
            .substringBefore("@JvmOverloads")
        assertTrue(block.contains("[诊断]请求 useWebView="))
        assertOrder(
            block,
            "AppLog.putNotSave(",
            "if (this.useWebView && useWebView) {"
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
