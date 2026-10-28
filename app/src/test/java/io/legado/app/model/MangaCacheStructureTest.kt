package io.legado.app.model

import io.legado.app.utils.DiagSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整本漫画下载的**结构断言**测试。
 *
 * 这里只验证「代码结构与接线是否正确」，**不声称已经验证运行期行为**。
 * 涉及网络、真实缓存目录、通知渲染、Glide 解码的行为必须真机验收，
 * 清单见交付报告。
 *
 * 覆盖意图：
 * - 通知 ID / 任务状态与小说下载隔离；
 * - 图片并发受限、逐章推进、已缓存跳过、失败可重试；
 * - 阅读页本地优先 + 网络回退；
 * - 整本缓存标记不被既有清理策略删除。
 */
class MangaCacheStructureTest {

    private val mangaCachePath = "app/src/main/java/io/legado/app/model/MangaCache.kt"
    private val progressPath = "app/src/main/java/io/legado/app/model/MangaCacheProgress.kt"
    private val servicePath = "app/src/main/java/io/legado/app/service/MangaCacheService.kt"
    private val mangaVhPath =
        "app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt"
    private val mangaActivityPath = "app/src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt"
    private val bookHelpPath = "app/src/main/java/io/legado/app/help/book/BookHelp.kt"
    private val cacheBookPath = "app/src/main/java/io/legado/app/model/CacheBook.kt"
    private val manifestPath = "app/src/main/AndroidManifest.xml"
    private val mangaMenuPath = "app/src/main/res/menu/book_manga.xml"

    @Test
    fun `漫画下载服务使用独立通知ID且不碰小说缓存服务`() {
        val src = DiagSourceText.source(servicePath)
        //所有 notify 都必须走漫画自己的通知 ID
        assertEquals(
            2,
            DiagSourceText.countOf(src, "notificationManager.notify(NotificationId.MangaCacheService")
        )
        assertFalse(src, src.contains("NotificationId.CacheBookService"))
        //不能读写小说下载任务状态（KDoc 里提到类名不算，这里查成员访问）
        assertFalse("漫画服务不应读写小说下载状态", src.contains("CacheBook."))
        //与小说缓存一致的前台服务基础设施
        assertTrue(src, src.contains("BaseService"))
        assertTrue(src, src.contains("AppConst.channelIdDownload"))
        assertTrue(src, src.contains("IntentAction.stop"))
    }

    @Test
    fun `小说下载任务不受漫画下载影响`() {
        val src = DiagSourceText.source(cacheBookPath)
        assertFalse("CacheBook 不应感知漫画任务", src.contains("MangaCache"))
    }

    @Test
    fun `图片并发限制为四路且逐章推进`() {
        val src = DiagSourceText.source(mangaCachePath)
        assertTrue(src, src.contains("const val CONCURRENCY = 4"))
        assertTrue(src, src.contains("onEachParallel(MangaCache.CONCURRENCY)"))
        //逐章循环：一章处理完再进入下一章
        DiagSourceText.assertOrder(
            src,
            "for (index in startIndex..endIndex)",
            "downloadChapter(index)",
            "doneChapters.incrementAndGet()"
        )
        //不把全书图片一次性收集：整个文件只有 downloadChapter 里解析一次图片地址
        assertEquals(1, DiagSourceText.countOf(src, "BookHelp.flowImages("))
    }

    @Test
    fun `已缓存图片跳过 失败可重试`() {
        val src = DiagSourceText.source(mangaCachePath)
        val imageBlock = DiagSourceText.block(src, "private suspend fun downloadImage(", "\n    }\n\n}")
        //先判本地文件再下载
        DiagSourceText.assertOrder(
            imageBlock,
            "BookHelp.isImageUsable(book, url)",
            "retryDownload(MangaCache.MAX_RETRY)",
            "BookHelp.saveImageChecked("
        )
        assertTrue(imageBlock, imageBlock.contains("skippedImages.incrementAndGet()"))
        assertTrue(imageBlock, imageBlock.contains("failedImages.incrementAndGet()"))
        assertTrue(imageBlock, imageBlock.contains("successImages.incrementAndGet()"))
        //失败不能被吞掉后仍算成功
        assertTrue(imageBlock, imageBlock.contains("if (ok)"))
    }

    @Test
    fun `取消后不再调度新请求`() {
        val src = DiagSourceText.source(mangaCachePath)
        val runBlock = DiagSourceText.block(src, "private suspend fun run()", "private suspend fun downloadChapter")
        assertTrue(runBlock, runBlock.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(runBlock, runBlock.contains("if (state != State.RUNNING) break"))
        //取消态在 finally 里兜底，避免状态停在运行中
        assertTrue(runBlock, runBlock.contains("if (state == State.RUNNING) {"))
        assertTrue(src, src.contains("state = State.CANCELED"))
    }

    @Test
    fun `任务结束状态可区分成功 部分失败 全部失败 取消`() {
        val src = DiagSourceText.source(mangaCachePath)
        listOf("SUCCESS", "PARTIAL_FAILED", "ALL_FAILED", "CANCELED").forEach {
            assertTrue("缺少状态 $it", src.contains("State.$it ->"))
        }
    }

    @Test
    fun `重复启动不会创建重复任务`() {
        val src = DiagSourceText.source(mangaCachePath)
        assertTrue(src, src.contains("if (tasks.isNotEmpty()) return false"))
        assertTrue(src, src.contains("putIfAbsent"))
    }

    @Test
    fun `进度百分比只用已完成章节数`() {
        val src = DiagSourceText.source(progressPath)
        assertTrue(src, src.contains("doneChapters.toLong() * 100L / totalChapters"))
        //分母固定，不会因动态总数而倒退
        assertFalse(src, src.contains("totalImages"))
    }

    @Test
    fun `阅读页本地优先并保留网络回退`() {
        val src = DiagSourceText.source(mangaVhPath)
        val block = DiagSourceText.block(src, "private fun loadImage(", "private fun localImageFile(")
        DiagSourceText.assertOrder(
            block,
            "if (preferLocal) localImageFile(imageUrl) else null",
            "BookCover.loadMangaLocal(context, localFile, transformation)",
            "BookCover.loadManga("
        )
        //本地加载失败必须回退网络，避免整章永久打不开
        assertTrue(block, block.contains("preferLocal = false"))
        //命中判定要求文件非空，避免把 0 字节文件当成命中
        val localBlock = DiagSourceText.block(src, "private fun localImageFile(", "\n}")
        assertTrue(localBlock, localBlock.contains("BookHelp.isImageUsable(book, imageUrl)"))
        assertTrue(localBlock, localBlock.contains("BookHelp.getImage(book, imageUrl)"))
        //本地文件已是解码内容，不能再套 Glide 的磁盘缓存
        val coverSrc = DiagSourceText.source("app/src/main/java/io/legado/app/model/BookCover.kt")
        val localLoader = DiagSourceText.block(coverSrc, "fun loadMangaLocal(", "fun preloadManga(")
        assertTrue(localLoader, localLoader.contains("DiskCacheStrategy.NONE"))
    }

    @Test
    fun `整本缓存标记不会被既有清理策略删除`() {
        val src = DiagSourceText.source(bookHelpPath)
        val clearBlock = DiagSourceText.block(
            src, "private fun clearComicCache(book: Book) {", "val chapterList"
        )
        assertTrue(clearBlock, clearBlock.contains("if (isMangaFullCached(book))"))
        assertTrue(clearBlock, clearBlock.contains("return"))
        //标记文件放在书目录下而不是 images/ 内
        assertTrue(src, src.contains("private const val mangaFullCacheMarkName"))
        val markBlock = DiagSourceText.block(src, "fun markMangaFullCached(", "fun isMangaFullCached(")
        assertFalse("标记文件不能放进 images 目录", markBlock.contains("cacheImageFolderName"))
    }

    @Test
    fun `下载接口显式返回成败而不吞异常`() {
        val src = DiagSourceText.source(bookHelpPath)
        val checked = DiagSourceText.block(src, "suspend fun saveImageChecked(", "fun markMangaFullCached(")
        assertTrue(checked, checked.contains("return false"))
        assertTrue(checked, checked.contains("throw e"))
        assertTrue(checked, checked.contains("catch (e: CancellationException)"))
        //原有 saveImage 语义保持不变
        val legacy = DiagSourceText.block(src, "suspend fun saveImage(", "fun getImage(book: Book")
        assertTrue(legacy, legacy.contains("AppLog.put(msg, e)"))
        assertFalse("不应改动小说/正文插图使用的原接口签名", legacy.contains("): Boolean"))
    }

    @Test
    fun `菜单与服务已在清单中接线`() {
        val menu = DiagSourceText.source(mangaMenuPath)
        assertTrue(menu, menu.contains("menu_download_all"))
        assertTrue(menu, menu.contains("menu_download_all_manga"))

        val activity = DiagSourceText.source(mangaActivityPath)
        assertTrue(activity, activity.contains("R.id.menu_download_all -> downloadWholeManga()"))
        assertTrue(activity, activity.contains("MangaCache.start("))
        //默认整本：0 到最后一章
        assertTrue(activity, activity.contains("0, chapterCount - 1"))

        val manifest = DiagSourceText.source(manifestPath)
        val serviceEntry = DiagSourceText.block(manifest, ".service.MangaCacheService", "/>")
        assertTrue(serviceEntry, serviceEntry.contains("foregroundServiceType=\"dataSync\""))
    }

}
