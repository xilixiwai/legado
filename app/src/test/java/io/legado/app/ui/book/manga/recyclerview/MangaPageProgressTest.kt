package io.legado.app.ui.book.manga.recyclerview

import io.legado.app.utils.DiagSourceText.assertOrder
import io.legado.app.utils.DiagSourceText.block
import io.legado.app.utils.DiagSourceText.countOf
import io.legado.app.utils.DiagSourceText.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中央加载百分比与全本下载四状态通知的验证。
 *
 * - [MangaPageProgress] 纯逻辑直接单测：只有真实下载字节才产生百分比；
 * - 结构断言证明：进度响应体只在有监听时包装（其余请求零开销）、
 *   MangaVH 回调先过绑定身份校验、重绑/回收/失败移除监听、
 *   ensureImageBatch 的 onStart 在并发槽取得之后才触发、小说链路不感知 onStart。
 *
 * 真机边界：通知与 TextView 实际渲染、真实网络分块的回调频率需真机验收。
 */
class MangaPageProgressTest {

    // ---------- 行为：MangaPageProgress ----------

    @Test
    fun `有可靠总字节数时显示真实百分比`() {
        val p = MangaPageProgress()
        assertEquals(0, p.onEvent(0, 1024L))
        assertEquals(35, p.onEvent(35, 1024L))
        assertEquals(100, p.onEvent(100, 1024L))
    }

    @Test
    fun `相同整数百分比合并为一次显示`() {
        val p = MangaPageProgress()
        assertEquals(35, p.onEvent(35, 1024L))
        assertNull("同一整数百分比的连续分块不再刷新", p.onEvent(35, 1024L))
        assertEquals(36, p.onEvent(36, 1024L))
    }

    @Test
    fun `无可靠总字节数或引导事件不产生百分比`() {
        val p = MangaPageProgress()
        assertNull("addListener 的 (0,0,0) 引导调用不显示", p.onEvent(0, 0L))
        assertNull("分块传输无 Content-Length 不显示", p.onEvent(0, -1L))
        assertNull("未知总长不得伪造百分比", p.onEvent(0, -1L))
    }

    @Test
    fun `每次绑定新建实例旧图片进度不污染新图片`() {
        val old = MangaPageProgress().apply { onEvent(80, 1000L) }
        val fresh = MangaPageProgress()
        //旧图已显示 80%，新实例不受其影响，从自己的真实进度开始
        assertEquals("新图片显示自己的真实进度", 0, fresh.onEvent(0, 500L))
        //隔离靠实例：旧实例仍只跟踪自己的下载流
        assertEquals(81, old.onEvent(81, 1000L))
    }

    // ---------- 结构：接线与防护 ----------

    @Test
    fun `进度响应体只在有监听时包装其余请求零开销`() {
        val src = source("app/src/main/java/io/legado/app/help/http/HttpHelper.kt")
        val interceptor = block(src, "val okHttpClient: OkHttpClient by lazy", "val okHttpClientManga")
        assertTrue("无监听时直接放行不包装", interceptor.contains("if (ProgressManager.getProgressListener(url) == null)"))
        assertOrder(
            interceptor,
            "getProgressListener(url) == null",
            "ProgressResponseBody(url, LISTENER, response.body)"
        )
    }

    @Test
    fun `中央百分比回调过身份校验且过期监听被移除`() {
        val vh = source("app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt")
        val register = block(vh, "private fun registerDownloadProgress(", "private fun removeProgressListener")
        assertOrder(
            register,
            "ProgressManager.addListener(imageUrl)",
            "canDeliverToUi(sequence, imageUrl)",
            "progress.onEvent(percentage, totalBytes)"
        )
        val remove = block(vh, "private fun removeProgressListener", "private fun canDeliverToUi")
        assertTrue(remove.contains("ProgressManager.removeListener(it)"))
        //绑定起点不显示虚假百分比（缓存命中只保留转圈）
        val loadBlock = block(vh, "fun loadImageWithRetry(", "fun cancelLoad()")
        assertOrder(loadBlock, "mProgress.isVisible = false", "registerDownloadProgress(imageUrl, sequence)")
        //重绑/回收/失败三条路径都要摘除监听
        val cancelBlock = block(vh, "fun cancelLoad()", "private fun registerDownloadProgress")
        assertTrue("回收必须移除进度监听", cancelBlock.contains("removeProgressListener()"))
        val failBlock = block(vh, "private fun showFail(", "companion object")
        assertTrue("失败必须移除进度监听", failBlock.contains("removeProgressListener()"))
    }

    @Test
    fun `onStart在并发槽取得之后才触发且排队任务不会计入正在下载`() {
        val src = source("app/src/main/java/io/legado/app/model/MangaImageCache.kt")
        val batchBlock = block(src, "suspend fun ensureImageBatch(", "data class ImageFileInfo")
        assertOrder(
            batchBlock,
            "permits.withPermit {",
            "coroutineContext.ensureActive()",
            "onStart()",
            "ensure(url)",
            "onResult(result)"
        )
    }

    @Test
    fun `任务侧在锁内维护正在下载计数且结束后归零规则在快照侧`() {
        val cache = source("app/src/main/java/io/legado/app/model/MangaCache.kt")
        val downloadCall = block(cache, "download(index, { total ->", "catch (e: CancellationException)")
        assertTrue("onStart 计数必须持 progressLock", downloadCall.contains("synchronized(progressLock) { downloadingImages++ }"))
        assertTrue("终态回调必须扣减在途计数", downloadCall.contains("if (downloadingImages > 0) downloadingImages--"))
        val snapshot = block(cache, "fun snapshot(): MangaCacheProgress", "fun finishText()")
        assertTrue("快照携带原始在途计数", snapshot.contains("downloadingImages = downloadingImages"))
    }

    @Test
    fun `小说下载不感知开始回调`() {
        val cacheBook = source("app/src/main/java/io/legado/app/model/CacheBook.kt")
        //流操作符 flow.onStart 与本回调无关；断言小说没有向统一入口传递 onStart 回调
        assertEquals("小说链路不得传递 onStart 回调", 0, countOf(cacheBook, "onStart ="))
    }
}
