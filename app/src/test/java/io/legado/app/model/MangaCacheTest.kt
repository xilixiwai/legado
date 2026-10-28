package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整本漫画下载的纯逻辑回归测试。
 *
 * 覆盖：章节范围归一化、进度百分比不倒退、通知文案、单张图片的重试策略。
 * 这些都不依赖 Android / 数据库 / 网络，可以直接在 JVM 上跑。
 *
 * 需要网络与真实缓存目录的部分（跳过已缓存图片、失败计数落到任务状态、
 * 取消后不再调度新请求、阅读页命中本地文件）见 [MangaCacheStructureTest]
 * 的结构断言与报告中的真机验收清单。
 */
class MangaCacheTest {

    private fun book(name: String = "测试漫画", url: String = "https://a.test/1/") =
        Book(bookUrl = url, name = name, author = "作者")

    private fun progress(
        done: Int,
        total: Int,
        curDone: Int = 0,
        curTotal: Int = 0,
        failedImages: Int = 0,
        failedChapters: Int = 0,
        running: Boolean = true,
    ) = MangaCacheProgress(
        bookName = "测试漫画",
        doneChapters = done,
        totalChapters = total,
        curChapterDone = curDone,
        curChapterTotal = curTotal,
        failedImages = failedImages,
        failedChapters = failedChapters,
        running = running,
    )

    // ---------- 1. 下载范围 ----------

    @Test
    fun `整本范围覆盖全部章节`() {
        val task = MangaCacheTask(book(), BookSource(), 0, 99, 100)
        assertEquals(0, task.startIndex)
        assertEquals(99, task.endIndex)
        assertEquals(100, task.totalChapters)
    }

    @Test
    fun `越界范围被裁剪到有效章节`() {
        val task = MangaCacheTask(book(), BookSource(), -5, 500, 100)
        assertEquals(0, task.startIndex)
        assertEquals(99, task.endIndex)
        assertEquals(100, task.totalChapters)
    }

    @Test
    fun `起点大于终点时收敛为单章`() {
        val task = MangaCacheTask(book(), BookSource(), 50, 10, 100)
        assertEquals(50, task.startIndex)
        assertEquals(50, task.endIndex)
        assertEquals(1, task.totalChapters)
    }

    @Test
    fun `单章书籍范围正确`() {
        val task = MangaCacheTask(book(), BookSource(), 0, 0, 1)
        assertEquals(0, task.startIndex)
        assertEquals(0, task.endIndex)
        assertEquals(1, task.totalChapters)
    }

    // ---------- 2. 任务初始状态 ----------

    @Test
    fun `初始快照为运行中且计数为零`() {
        val snapshot = MangaCacheTask(book(), BookSource(), 0, 99, 100).snapshot()
        assertEquals(0, snapshot.doneChapters)
        assertEquals(100, snapshot.totalChapters)
        assertEquals(0, snapshot.failedImages)
        assertEquals(0, snapshot.failedChapters)
        assertEquals(0, snapshot.percent)
        assertTrue(snapshot.running)
    }

    @Test
    fun `取消后任务不再处于运行中`() {
        val task = MangaCacheTask(book(), BookSource(), 0, 99, 100)
        task.cancel()
        assertEquals(MangaCacheTask.State.CANCELED, task.state)
        assertFalse(task.snapshot().running)
    }

    // ---------- 3. 进度百分比 ----------

    @Test
    fun `整体进度百分比单调不减`() {
        val total = 480
        var last = -1
        for (done in 0..total) {
            val percent = progress(done, total).percent
            assertTrue("done=$done 的百分比 $percent 小于前一次 $last", percent >= last)
            last = percent
        }
        assertEquals(100, last)
    }

    @Test
    fun `百分比被限制在 0 到 100`() {
        assertEquals(0, progress(0, 0).percent)
        assertEquals(0, progress(-1, 100).percent)
        assertEquals(100, progress(999, 100).percent)
    }

    // ---------- 4. 通知文案 ----------

    @Test
    fun `运行中章节标签显示正在处理的下一章`() {
        assertEquals("1/480", progress(0, 480).chapterLabel)
        assertEquals("13/480", progress(12, 480).chapterLabel)
        assertEquals("480/480", progress(479, 480).chapterLabel)
        //最后一章处理完但状态尚未翻转，也不能溢出
        assertEquals("480/480", progress(480, 480).chapterLabel)
    }

    @Test
    fun `结束后章节标签不再加一`() {
        assertEquals("480/480", progress(480, 480, running = false).chapterLabel)
        assertEquals("0/480", progress(0, 480, running = false).chapterLabel)
        assertEquals("0/0", progress(0, 0).chapterLabel)
    }

    @Test
    fun `通知文案包含漫画名 章节 图片与失败数`() {
        val text = progress(12, 480, curDone = 8, curTotal = 24, failedImages = 3).text
        assertTrue(text, text.contains("测试漫画"))
        assertTrue(text, text.contains("13/480"))
        assertTrue(text, text.contains("8/24"))
        assertTrue(text, text.contains("失败 3"))
        //章节级失败只在出现时追加
        assertFalse(text, text.contains("章节失败"))
    }

    @Test
    fun `有整章失败时文案追加章节失败数`() {
        val text = progress(12, 480, failedImages = 3, failedChapters = 2).text
        assertTrue(text, text.contains("章节失败 2"))
    }

    @Test
    fun `文案不展示未预扫描的总图片数`() {
        //全书图片总数在下载前不可知，不能当成精确值展示
        val text = progress(12, 480, curDone = 8, curTotal = 24).text
        assertFalse(text, text.contains("总图片"))
    }

    // ---------- 5. 单张图片重试 ----------

    @Test
    fun `首次成功不重试`() {
        runBlocking {
            var attempts = 0
            val ok = retryDownload(2) {
                attempts++
                true
            }
            assertTrue(ok)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `失败后最多重试指定次数`() {
        runBlocking {
            var attempts = 0
            val delays = mutableListOf<Int>()
            val ok = retryDownload(2, onRetryDelay = { delays.add(it) }) {
                attempts++
                false
            }
            assertFalse(ok)
            assertEquals(3, attempts)
            assertEquals(listOf(1, 2), delays)
        }
    }

    @Test
    fun `重试后成功立即停止`() {
        runBlocking {
            var attempts = 0
            val ok = retryDownload(3) {
                attempts++
                attempts >= 2
            }
            assertTrue(ok)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `重试次数为零时只尝试一次`() {
        runBlocking {
            var attempts = 0
            val ok = retryDownload(0) {
                attempts++
                false
            }
            assertFalse(ok)
            assertEquals(1, attempts)
        }
    }

}
