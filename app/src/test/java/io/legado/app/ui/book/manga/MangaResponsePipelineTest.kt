package io.legado.app.ui.book.manga

import io.legado.app.help.http.readObservedImageBytes
import io.legado.app.model.ImageCacheResult
import io.legado.app.model.ImageStore
import io.legado.app.model.PersistentImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

/** 使用生产缓存器和响应读取入口，存储/图片校验用隔离假数据；不访问用户缓存或数据库。 */
class MangaResponsePipelineTest {
    private val url = "https://fake.example/p.png"
    private val page = MangaPageIdentity.create("book", "source", "chapter", 0, url)
    private class Store(private val write: Boolean = true, private val readable: Boolean = true) : ImageStore {
        var stored = false
        override fun exists(key: String) = stored
        override fun isUsable(key: String) = stored && readable
        override fun delete(key: String) { error("must not delete") }
        override fun writeAtomic(key: String, bytes: ByteArray): Boolean {
            stored = write
            return write
        }
    }

    @Test fun `fetch validation write and readback success are separate from display`() = runBlocking {
        for ((write, readable) in listOf(true to true, false to true, true to false)) {
            val diagnostics = MangaPageDiagnostics()
            val observer = diagnostics.begin(page, url)
            val store = Store(write, readable)
            var requests = 0
            var validations = 0
            var delivered = 0
            val cache = PersistentImageCache(store,
                isValidData = { validations++; observer.validated(true); true },
                fetch = {
                    requests++
                    val bytes = readObservedImageBytes(observer) {
                        Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("ok").header("Content-Type", "image/png")
                            .body(byteArrayOf(1, 2, 3).toResponseBody("image/png".toMediaType())).build()
                    }
                    observer.decrypted(false, bytes.size)
                    bytes
                })
            val result = cache.ensure(url) { delivered++ }
            observer.cached(result)
            observer.displayed()
            assertEquals(if (write && readable) ImageCacheResult.STORED else ImageCacheResult.FAILED, result)
            assertEquals(1, requests)
            assertEquals(1, validations)
            assertEquals(1, delivered)
            val info = diagnostics.snapshot(page)!!
            assertEquals(result, info.cache)
            assertEquals(200, info.status)
            assertEquals("图片最终显示成功", info.display)
            if (result == ImageCacheResult.STORED) {
                val hitObserver = diagnostics.begin(page, url)
                hitObserver.cached(cache.ensure(url))
                assertEquals(1, requests)
                assertEquals(ImageCacheResult.HIT, diagnostics.snapshot(page)!!.cache)
                assertFalse(diagnostics.snapshot(page)!!.hasResponse)
            }
        }
    }

    @Test fun `cancelling during body acquisition propagates through production cache`() = runBlocking {
        val diagnostics = MangaPageDiagnostics()
        val observer = diagnostics.begin(page, url)
        val entered = CompletableDeferred<Unit>()
        val store = Store()
        val cache = PersistentImageCache(store, { true }, {
            readObservedImageBytes(observer) {
                entered.complete(Unit)
                CompletableDeferred<Response>().await()
            }
        })
        val job = launch {
            try { cache.ensure(url); fail("cancelled request cannot succeed") }
            catch (e: CancellationException) { observer.cancelled(); throw e }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(store.stored)
        assertEquals("任务取消", diagnostics.snapshot(page)!!.failureStage)
    }

    @Test fun `late callbacks on another thread cannot overwrite replacement`() {
        val diagnostics = MangaPageDiagnostics()
        val old = diagnostics.begin(page, url)
        val start = CountDownLatch(1)
        val thread = Thread {
            start.await()
            repeat(500) { old.cached(ImageCacheResult.FAILED); old.displayed(); old.cancelled() }
        }.apply { start() }
        val current = diagnostics.begin(page, url)
        current.cached(ImageCacheResult.STORED)
        start.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertEquals(ImageCacheResult.STORED, diagnostics.snapshot(page)!!.cache)
        assertNull(diagnostics.snapshot(page)!!.display)
    }

    @Test fun `cancellation freezes this request partial response`() {
        val diagnostics = MangaPageDiagnostics()
        val observer = diagnostics.begin(page, url)
        observer.cancelled()
        val cancelled = diagnostics.snapshot(page)
        observer.cached(ImageCacheResult.STORED)
        observer.displayed()
        assertEquals(cancelled, diagnostics.snapshot(page))
    }

    @Test fun `menu uses center page and gives display and copy the same full redacted text`() {
        val activity = source("ui/book/manga/ReadMangaActivity.kt")
        val menu = activity.substringAfter("R.id.menu_manga_response ->").substringBefore("R.id.menu_pre_manga_number")
        assertTrue(menu.contains("responseText(binding.recyclerView.findCenterViewPosition())"))
        assertTrue(menu.contains("text, copyable = true"))
        assertTrue(menu.contains("maxDisplayChars = text.length + 1"))
        assertFalse(menu.contains("ensureImage"))
        assertFalse(menu.contains("getByteArrayAwait"))
        val dialog = source("ui/widget/dialog/TextDialog.kt")
        assertTrue(dialog.contains("copyContent = content"))
        assertTrue(dialog.contains("binding.textView.text = content"))
        val adapter = source("ui/book/manga/recyclerview/MangaAdapter.kt")
        assertTrue(adapter.contains("ch.pages.any { it === page }"))
        assertTrue(adapter.contains("diagnosticIdentity(current) == boundIdentity"))
        assertFalse(adapter.contains("currentList[layoutPosition]"))
        assertTrue(activity.contains("imageDiagnostics.close()"))
        assertTrue(activity.contains("imageDiagnostics.clear()"))
    }

    @Test fun `observation is opt in and production cache still controls success`() {
        val analyze = source("model/analyzeRule/AnalyzeUrl.kt")
        assertTrue(analyze.contains("suspend fun getByteArrayAwait(): ByteArray = getByteArrayAwait(null)"))
        assertTrue(analyze.contains("readObservedImageBytes(observer)"))
        val book = source("help/book/BookHelp.kt")
        val ensure = book.substringAfter("suspend fun ensureImage(").substringBefore("suspend fun ensureChapterImages(")
        assertTrue(ensure.contains("observer: ImageRequestObserver? = null"))
        assertTrue(ensure.contains(".ensure(src, onImageData)"))
        val batch = book.substringAfter("suspend fun ensureChapterImages(").substringBefore("private fun imageCache(")
        assertFalse(batch.contains("observer"))
        val vh = source("ui/book/manga/recyclerview/MangaVH.kt")
        val fallback = vh.substringAfter("private fun loadNetworkFallback(").substringBefore("private fun emitReady(")
        assertFalse(fallback.contains("observer"))
        assertFalse(fallback.contains("ResponseBody"))
    }

    private fun source(relative: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/src/main/java/io/legado/app/$relative").readText()
    }
}
