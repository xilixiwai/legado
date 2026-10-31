package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.writeImageCandidate
import io.legado.app.utils.JvmImageFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 实际任务循环 + 实际批处理；替换章节 IO，不使用数据库、网络或用户缓存。 */
class MangaCacheTaskProgressTest {
    @get:Rule val temp = TemporaryFolder()

    private fun task(chapters: Int = 1) = MangaCacheTask(
        Book(bookUrl = "https://test/book", name = "漫画", author = "作者"),
        BookSource(), 0, chapters - 1, chapters,
    )

    private suspend fun MangaCacheTask.process(
        download: suspend (Int, (Int) -> Unit, (ImageCacheResult) -> Unit) -> Unit,
    ) = processChapters(download, onChapterDone = {}, onChapterError = { _, _ -> })

    private suspend fun batch(
        results: List<ImageCacheResult>,
        onTotal: (Int) -> Unit,
        onResult: (ImageCacheResult) -> Unit,
    ) {
        onTotal(results.size)
        ensureImageBatch(results.indices.map(Int::toString), 4, onResult = onResult) {
            results[it.toInt()]
        }
    }

    @Test
    fun `全部缓存命中计成功但不计新增`() = runBlocking {
        val task = task()
        task.process { _, total, result -> batch(List(5) { ImageCacheResult.HIT }, total, result) }
        val p = task.snapshot()
        assertEquals(5, p.discoveredImages)
        assertEquals(5, p.processedImages)
        assertEquals(5, p.cachedImages)
        assertEquals(0, p.storedImages)
        assertEquals(0, p.failedImages)
        assertEquals(100, p.percent)
        assertEquals(MangaCacheTask.State.SUCCESS, task.state)
    }

    @Test
    fun `全部首次保存成功`() = runBlocking {
        val task = task()
        task.process { _, total, result -> batch(List(7) { ImageCacheResult.STORED }, total, result) }
        val p = task.snapshot()
        assertEquals(7, p.processedImages)
        assertEquals(7, p.successImages)
        assertEquals(7, p.storedImages)
        assertEquals(0, p.cachedImages)
        assertEquals(MangaCacheTask.State.SUCCESS, task.state)
    }

    @Test
    fun `部分失败计入处理进度但不计入成功`() = runBlocking {
        val task = task()
        task.process { _, total, result ->
            batch(listOf(ImageCacheResult.HIT, ImageCacheResult.STORED, ImageCacheResult.FAILED), total, result)
        }
        val p = task.snapshot()
        assertEquals(3, p.processedImages)
        assertEquals(2, p.successImages)
        assertEquals(1, p.failedImages)
        assertEquals(100, p.percent) //处理完成，不是百分之百成功
        assertEquals(MangaCacheTask.State.PARTIAL_FAILED, task.state)
        assertTrue(task.finishText().contains("已处理 3/3"))
        assertTrue(task.finishText().contains("部分失败"))
    }

    @Test
    fun `全部图片失败正确结束为全部失败`() = runBlocking {
        val task = task()
        task.process { _, total, result -> batch(List(4) { ImageCacheResult.FAILED }, total, result) }
        assertEquals(4, task.snapshot().processedImages)
        assertEquals(4, task.snapshot().failedImages)
        assertEquals(0, task.snapshot().successImages)
        assertEquals(MangaCacheTask.State.ALL_FAILED, task.state)
        assertTrue(task.finishText().contains("全部失败"))
    }

    @Test
    fun `逐章发现累计并在最后一章列表取得后才启用确定进度`() = runBlocking {
        val task = task(3)
        val totals = mutableListOf<Int>()
        task.process { index, total, result ->
            batch(List(index + 1) { ImageCacheResult.STORED }, {
                total(it)
                val p = task.snapshot()
                totals += p.discoveredImages
                assertEquals(index == 2, p.totalImagesKnown)
                assertEquals(index != 2, p.indeterminate)
            }, result)
        }
        assertEquals(listOf(1, 3, 6), totals)
        assertEquals(6, task.snapshot().processedImages)
        assertEquals(3, task.snapshot().doneChapters)
    }

    @Test
    fun `下一章正文解析期间不显示上一章的百分之百`() = runBlocking {
        val task = task(2)
        task.process { index, total, result ->
            if (index == 1) {
                val p = task.snapshot()
                assertEquals(3, p.discoveredImages)
                assertEquals(3, p.processedImages)
                assertEquals(0, p.curChapterDone)
                assertEquals(0, p.curChapterTotal)
                assertTrue(p.indeterminate)
                assertEquals(0, p.percent)
                assertTrue(p.text.contains("总数待定"))
            }
            batch(List(3) { ImageCacheResult.HIT }, total, result)
        }
    }

    @Test
    fun `章节图片差异悬殊时进度按图片加权`() = runBlocking {
        val task = task(2)
        task.process { index, total, result ->
            if (index == 0) {
                batch(listOf(ImageCacheResult.STORED), total, result)
            } else {
                total(99)
                assertFalse(task.snapshot().indeterminate)
                assertEquals(1, task.snapshot().percent) //一章完成不是50%
                ensureImageBatch((1..99).map(Int::toString), 4, onResult = {
                    result(it)
                    assertEquals(task.snapshot().processedImages, task.snapshot().percent)
                }) { ImageCacheResult.STORED }
            }
        }
        assertEquals(100, task.snapshot().processedImages)
        assertEquals(100, task.snapshot().percent)
    }

    @Test
    fun `章节解析失败单独累计且不伪造全书总数并继续后续章节`() = runBlocking {
        val task = task(3)
        val visited = mutableListOf<Int>()
        task.process { index, total, result ->
            visited += index
            if (index == 1) error("正文解析失败")
            batch(listOf(ImageCacheResult.HIT), total, result)
        }
        val p = task.snapshot()
        assertEquals(listOf(0, 1, 2), visited)
        assertEquals(1, p.failedChapters)
        assertEquals(0, p.failedImages)
        assertEquals(2, p.discoveredImages)
        assertEquals(2, p.processedImages)
        assertFalse(p.totalImagesKnown)
        assertTrue(p.indeterminate)
        assertEquals(MangaCacheTask.State.PARTIAL_FAILED, task.state)
    }

    @Test
    fun `全部章节正文不可用也不误报成功`() = runBlocking {
        val task = task(2)
        task.process { _, _, _ -> /* 缺章节或正文返回null，没有图片列表 */ }
        assertEquals(2, task.snapshot().failedChapters)
        assertEquals(0, task.snapshot().failedImages)
        assertFalse(task.snapshot().totalImagesKnown)
        assertEquals(MangaCacheTask.State.ALL_FAILED, task.state)
    }

    @Test
    fun `网络或写入失败算图片失败而非章节失败`() = runBlocking {
        val png = JvmImageFixture.png(0xFF123456.toInt())
        val saved = mutableMapOf<String, ByteArray>()
        val store = object : ImageStore {
            override fun exists(key: String) = saved.containsKey(key)
            override fun isUsable(key: String) = saved[key]?.let {
                JvmImageFixture.read(it.inputStream()) != null
            } ?: false
            override fun delete(key: String) { error("下载失败不能删除已有缓存") }
            override fun writeAtomic(key: String, bytes: ByteArray): Boolean {
                if (key == "write-failure") return false
                saved[key] = bytes
                return true
            }
        }
        val cache = PersistentImageCache(store, isValidData = { JvmImageFixture.read(it.inputStream()) != null }, fetch = {
            if (it == "network-failure") throw java.io.IOException("network failed")
            png
        })
        val task = task()
        task.process { _, total, result ->
            total(3)
            ensureImageBatch(listOf("success", "write-failure", "network-failure"), 1, onResult = result, ensure = cache::ensure)
        }
        val p = task.snapshot()
        assertEquals(3, p.processedImages)
        assertEquals(1, p.successImages)
        assertEquals(2, p.failedImages)
        assertEquals(0, p.failedChapters)
        assertEquals(setOf("success"), saved.keys)
        assertEquals(MangaCacheTask.State.PARTIAL_FAILED, task.state)
    }

    @Test
    fun `章节处理异常保留此前图片结果并单独报告章节失败`() = runBlocking {
        val task = task()
        task.process { _, total, result ->
            total(3)
            result(ImageCacheResult.STORED)
            error("章节处理异常")
        }
        val p = task.snapshot()
        assertEquals(3, p.discoveredImages)
        assertEquals(1, p.processedImages)
        assertEquals(1, p.storedImages)
        assertEquals(0, p.failedImages)
        assertEquals(1, p.failedChapters)
        assertEquals(33, p.percent)
        assertEquals(MangaCacheTask.State.PARTIAL_FAILED, task.state)
    }

    @Test
    fun `中途取消保留本章已完成结果且不计入取消中的图片`() = runBlocking {
        withTimeout(5000) {
            val task = task(2)
            val waiting = CompletableDeferred<Unit>()
            val job = launch {
                task.process { index, total, result ->
                    assertEquals(0, index) //不能进入下一章
                    total(4)
                    ensureImageBatch(listOf("hit", "stored", "failed", "waiting"), 1, onResult = result) {
                        when (it) {
                            "hit" -> ImageCacheResult.HIT
                            "stored" -> ImageCacheResult.STORED
                            "failed" -> ImageCacheResult.FAILED
                            else -> { waiting.complete(Unit); awaitCancellation() }
                        }
                    }
                }
            }
            waiting.await()
            task.cancel()
            job.cancelAndJoin()
            val p = task.snapshot()
            assertEquals(4, p.discoveredImages)
            assertEquals(3, p.processedImages)
            assertEquals(1, p.cachedImages)
            assertEquals(1, p.storedImages)
            assertEquals(1, p.failedImages)
            assertEquals(0, p.failedChapters)
            assertEquals(0, p.doneChapters)
            assertEquals(MangaCacheTask.State.CANCELED, task.state)
            assertTrue(task.finishText().contains("已处理 3/4"))
        }
    }

    @Test
    fun `取消保留实际写入的mcache并且后续任务命中不下载`() = runBlocking {
        withTimeout(5000) {
            val png = JvmImageFixture.png(0xFF123456.toInt())
            fun file(key: String) = File(temp.root, "$key.mcache")
            fun usable(file: File) = file.exists() && JvmImageFixture.read(file.inputStream()) != null
            val store = object : ImageStore {
                override fun exists(key: String) = file(key).exists()
                override fun isUsable(key: String) = usable(file(key))
                override fun delete(key: String) { error("取消不能删除已缓存图片") }
                override fun writeAtomic(key: String, bytes: ByteArray) =
                    writeImageCandidate(file(key), bytes, ::usable)
            }
            val waiting = CompletableDeferred<Unit>()
            var fetched = 0
            val cache = PersistentImageCache(store, isValidData = { JvmImageFixture.read(it.inputStream()) != null }, fetch = {
                fetched++
                if (it == "waiting") { waiting.complete(Unit); awaitCancellation() }
                png
            })
            val task = task()
            val job = launch {
                task.process { _, total, result ->
                    total(2)
                    ensureImageBatch(listOf("saved", "waiting"), 1, onResult = result, ensure = cache::ensure)
                }
            }
            waiting.await()
            job.cancelAndJoin()
            assertEquals(MangaCacheTask.State.CANCELED, task.state)
            assertEquals(1, task.snapshot().storedImages)
            assertEquals(1, task.snapshot().processedImages)
            assertArrayEquals(png, file("saved").readBytes())
            assertFalse(file("waiting").exists())
            val resumed = task()
            resumed.process { _, total, result ->
                total(1)
                ensureImageBatch(listOf("saved"), 1, onResult = result, ensure = cache::ensure)
            }
            assertEquals(2, fetched) //续任务没有第三次网络获取
            assertEquals(1, resumed.snapshot().cachedImages)
            assertEquals(MangaCacheTask.State.SUCCESS, resumed.state)
        }
    }

    @Test
    fun `重试只累计一次最终图片结果`() = runBlocking {
        val task = task()
        var attempts = 0
        task.process { _, total, result ->
            total(2)
            ensureImageBatch(listOf("eventual-success", "failure"), 1, onResult = result) { url ->
                var currentAttempt = 0
                val ok = retryDownload(2, onRetryDelay = {}) {
                    attempts++
                    currentAttempt++
                    url == "eventual-success" && currentAttempt == 3
                }
                if (ok) ImageCacheResult.STORED else ImageCacheResult.FAILED
            }
        }
        assertEquals(6, attempts)
        assertEquals(2, task.snapshot().processedImages)
        assertEquals(1, task.snapshot().storedImages)
        assertEquals(1, task.snapshot().failedImages)
    }

    @Test
    fun `四路并发累计与快照保持一致`() = runBlocking {
        val task = task()
        task.process { _, total, result ->
            total(400)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                ensureImageBatch((0 until 400).map(Int::toString), 4, onResult = {
                    result(it)
                    val p = task.snapshot()
                    assertEquals(p.cachedImages + p.storedImages + p.failedImages, p.processedImages)
                    assertEquals(p.curChapterDone, p.processedImages)
                }) { ImageCacheResult.entries[it.toInt() % 3] }
            }
        }
        val p = task.snapshot()
        assertEquals(400, p.processedImages)
        assertEquals(134, p.cachedImages)
        assertEquals(133, p.storedImages)
        assertEquals(133, p.failedImages)
    }

    @Test
    fun `无图片任务不会报告成功或确定的百分之百`() = runBlocking {
        val task = task()
        task.process { _, total, _ -> total(0) }
        assertEquals(MangaCacheTask.State.ALL_FAILED, task.state)
        assertTrue(task.snapshot().indeterminate)
        assertEquals(0, task.snapshot().percent)
    }
}
