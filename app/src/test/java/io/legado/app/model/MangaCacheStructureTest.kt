package io.legado.app.model

import io.legado.app.utils.DiagSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一持久化图片缓存的**结构断言**测试。
 *
 * 这里只验证「代码结构与接线是否正确」，**不声称已经验证运行期行为**。
 * 涉及网络、真实缓存目录、通知渲染、Glide 解码的行为必须真机验收，
 * 清单见交付报告。
 *
 * 覆盖意图：
 * - 三个入口（阅读 / 预下载 / 下载全部）共用同一个写盘入口；
 * - 下载全部以图片为单位统计，不再把章节完成数当成图片完成数；
 * - 完整缓存标记只在「整本 + 全部图片验证通过」时生成；
 * - 通知 ID / 任务状态与小说下载隔离，小说缓存链路不被改动。
 */
class MangaCacheStructureTest {

    private val mangaCachePath = "app/src/main/java/io/legado/app/model/MangaCache.kt"
    private val imageCachePath = "app/src/main/java/io/legado/app/model/MangaImageCache.kt"
    private val progressPath = "app/src/main/java/io/legado/app/model/MangaCacheProgress.kt"
    private val servicePath = "app/src/main/java/io/legado/app/service/MangaCacheService.kt"
    private val mangaVhPath =
        "app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaVH.kt"
    private val mangaAdapterPath =
        "app/src/main/java/io/legado/app/ui/book/manga/recyclerview/MangaAdapter.kt"
    private val mangaActivityPath = "app/src/main/java/io/legado/app/ui/book/manga/ReadMangaActivity.kt"
    private val readMangaPath = "app/src/main/java/io/legado/app/model/ReadManga.kt"
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
        //小说章节缓存仍走原有接口，行为不变
        assertTrue(src, src.contains("BookHelp.saveImages("))
    }

    @Test
    fun `漫画章节缓存在CacheBook里改走统一入口且与小说分支分开`() {
        val src = DiagSourceText.source(cacheBookPath)
        val firstBranch = DiagSourceText.block(
            src, "if (BookHelp.hasContent(book, chapter)) {", "WebBook.getContent("
        )
        assertTrue(firstBranch, firstBranch.contains("if (book.isImage)"))
        DiagSourceText.assertOrder(
            firstBranch,
            "BookHelp.ensureChapterImages(",
            "BookHelp.saveImages("
        )
        //首次缓存正文成功后也要落图，否则「缓存了这一章」只有正文
        val webBookBranch = DiagSourceText.block(src, "WebBook.getContent(", "downloadFinish(chapter, content)")
        assertTrue(webBookBranch, webBookBranch.contains("BookHelp.ensureChapterImages("))
    }

    @Test
    fun `三个入口共用同一个写盘实现`() {
        val bookHelp = DiagSourceText.source(bookHelpPath)
        //唯一写盘点
        assertTrue(bookHelp, bookHelp.contains("private fun writeImageAtomic("))
        assertTrue(bookHelp, bookHelp.contains("private class BookImageStore"))
        assertTrue(bookHelp, bookHelp.contains("PersistentImageCache("))
        //阅读页
        val vh = DiagSourceText.source(mangaVhPath)
        assertTrue(vh, vh.contains("BookHelp.ensureImage("))
        //预下载
        val adapter = DiagSourceText.source(mangaAdapterPath)
        assertTrue(adapter, adapter.contains("BookHelp.prefetchImage("))
        //下载全部
        val cache = DiagSourceText.source(mangaCachePath)
        assertTrue(cache, cache.contains("BookHelp.ensureChapterImages("))
        //预下载不再只预热 Glide
        assertFalse("预加载器不应再走只写临时缓存的 Glide 预加载", adapter.contains("BookCover.preloadManga("))
    }

    @Test
    fun `缓存键与目录名共用同一套规则`() {
        val keys = DiagSourceText.source(imageCachePath)
        assertTrue(keys, keys.contains("fun imageFileName(src: String)"))
        assertTrue(keys, keys.contains("MD5Utils.md5Encode16(src)"))
        //键保持「绝对地址 MD5」，不改成章节序号
        assertFalse(keys, keys.contains("chapterIndex /"))
        val bookHelp = DiagSourceText.source(bookHelpPath)
        assertTrue(bookHelp, bookHelp.contains("MangaImageKeys.imageFileName(src)"))
        //清理逻辑也必须用同一套键
        assertTrue(bookHelp, bookHelp.contains("imgNames.add(MangaImageKeys.imageFileName(mSrc))"))
    }

    @Test
    fun `图片并发限制为四路且逐章推进`() {
        val src = DiagSourceText.source(mangaCachePath)
        assertTrue(src, src.contains("const val CONCURRENCY = 4"))
        assertTrue(src, src.contains("concurrency = MangaCache.CONCURRENCY"))
        //逐章循环：一章处理完再进入下一章
        DiagSourceText.assertOrder(
            src,
            "for (index in startIndex..endIndex)",
            "downloadChapter(index)",
            "doneChapters.incrementAndGet()"
        )
        //章节级只解析一次：不把全书图片一次性收集
        assertEquals(1, DiagSourceText.countOf(src, "BookHelp.ensureChapterImages("))
        assertFalse("不应再一次性收集全书图片", src.contains("allImages"))
    }

    @Test
    fun `下载全部以图片为处理单位统计`() {
        val src = DiagSourceText.source(mangaCachePath)
        DiagSourceText.assertOrder(
            src,
            "BookHelp.ensureChapterImages(",
            "processedImages.addAndGet(stats.done)",
            "skippedImages.addAndGet(stats.hit)",
            "successImages.addAndGet(stats.stored)",
            "failedImages.addAndGet(stats.failed)"
        )
        //正文有但没有图片，不能算「这一章下完了」
        assertTrue(src, src.contains("emptyChapters.incrementAndGet()"))
        //卷节点单独计数，不污染失败统计
        assertTrue(src, src.contains("volumeChapters.incrementAndGet()"))
        //单张失败重试次数有上限
        assertTrue(src, src.contains("retryEach = MangaCache.MAX_RETRY"))
    }

    @Test
    fun `完整缓存标记只在整本且全部图片验证通过时生成`() {
        val src = DiagSourceText.source(mangaCachePath)
        val runBlock = DiagSourceText.block(src, "private suspend fun run()", "private suspend fun downloadChapter")
        assertTrue("一张图都没处理到不能算成功", runBlock.contains("processedImages.get() <= 0 -> State.ALL_FAILED"))
        assertTrue("无图章节不能算成功", runBlock.contains("emptyChapters.get() == 0"))
        assertTrue("失败图片不能算成功", runBlock.contains("failedImages.get() == 0"))
        assertTrue("失败章节不能算成功", runBlock.contains("failedChapters.get() == 0"))
        DiagSourceText.assertOrder(
            runBlock,
            "state = when {",
            "if (state == State.SUCCESS && isWholeBook)",
            "BookHelp.markMangaFullCached(book)"
        )
        //取消路径不能落标记
        assertFalse(runBlock, runBlock.contains("State.CANCELED -> BookHelp.markMangaFullCached"))
    }

    @Test
    fun `坏文件会被撤销整本标记`() {
        val src = DiagSourceText.source(bookHelpPath)
        val storeBlock = DiagSourceText.block(src, "private class BookImageStore", "suspend fun saveImageChecked(")
        assertTrue(storeBlock, storeBlock.contains("BookHelp.unmarkMangaFullCached(book)"))
        assertTrue(src, src.contains("fun unmarkMangaFullCached(book: Book)"))
    }

    @Test
    fun `统一入口先删坏文件再下载且校验后才算成功`() {
        val src = DiagSourceText.source(imageCachePath)
        val ensureBlock = DiagSourceText.block(src, "private suspend fun ensureLocked(", "private fun pruneLocks()")
        DiagSourceText.assertOrder(
            ensureBlock,
            "if (store.isUsable(key)) return ImageCacheResult.HIT",
            "if (store.exists(key))",
            "store.delete(key)",
            "fetch(key)",
            "isValidData(bytes)",
            "store.writeAtomic(key, bytes)"
        )
        //错误页/空响应一律不写盘
        assertTrue(ensureBlock, ensureBlock.contains("bytes.isEmpty() || !isValidData(bytes)"))
        //写后回读
        assertTrue(ensureBlock, ensureBlock.contains("if (!store.isUsable(key))"))
    }

    @Test
    fun `取消后不再调度新请求`() {
        val src = DiagSourceText.source(mangaCachePath)
        val runBlock = DiagSourceText.block(src, "private suspend fun run()", "private suspend fun downloadChapter")
        assertTrue(runBlock, runBlock.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(runBlock, runBlock.contains("if (state != State.RUNNING) break"))
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
    fun `阅读页先落盘再显示本地文件`() {
        val src = DiagSourceText.source(mangaVhPath)
        val loadBlock = DiagSourceText.block(src, "fun loadImageWithRetry(", "fun cancelLoad()")
        DiagSourceText.assertOrder(
            loadBlock,
            "BookHelp.ensureImage(source, book, imageUrl, chapter)",
            "loadFromCache(imageUrl"
        )
        //显示阶段只读本地文件，不联网
        val cacheBlock = DiagSourceText.block(src, "private fun loadFromCache(", "private fun loadNetworkFallback(")
        assertTrue(cacheBlock, cacheBlock.contains("BookCover.loadMangaLocal(context, file, transformation)"))
        assertFalse("本地显示路径不能直接走网络", cacheBlock.contains("BookCover.loadManga("))
        //本地文件被删掉后还有一次网络兜底
        assertTrue(cacheBlock, cacheBlock.contains("file.delete()"))
        assertTrue(cacheBlock, cacheBlock.contains("loadNetworkFallback("))
        //命中判定要求可解码
        val localBlock = DiagSourceText.block(src, "private fun localImageFile(", "\n}")
        assertTrue(localBlock, localBlock.contains("BookHelp.isImageUsable(book, imageUrl)"))
        //Glide 的临时缓存不能作为持久化手段
        val coverSrc = DiagSourceText.source("app/src/main/java/io/legado/app/model/BookCover.kt")
        val localLoader = DiagSourceText.block(coverSrc, "fun loadMangaLocal(", "fun preloadManga(")
        assertTrue(localLoader, localLoader.contains("DiskCacheStrategy.NONE"))
    }

    @Test
    fun `阅读页在复用时取消在途加载`() {
        val src = DiagSourceText.source(mangaVhPath)
        assertTrue(src, src.contains("fun cancelLoad()"))
        assertTrue(src, src.contains("cancelChildren()"))
        val adapter = DiagSourceText.source(mangaAdapterPath)
        assertTrue(adapter, adapter.contains("vh.cancelLoad()"))
    }

    @Test
    fun `预下载与阅读共用地址提取规则`() {
        val readManga = DiagSourceText.source(readMangaPath)
        val chapter = DiagSourceText.block(readManga, "private suspend fun getManageChapter(", "val imageCount = list.size")
        assertTrue("阅读页必须使用与下载一致的提取器", chapter.contains("BookHelp.flowMangaImages("))
        assertFalse("不能继续使用会命中占位图的旧提取", chapter.contains("BookHelp.flowImages("))
        val bookHelp = DiagSourceText.source(bookHelpPath)
        assertTrue(bookHelp, bookHelp.contains("MangaImageExtractor.extract("))
        //小说正文插图路径保持不变
        assertTrue(bookHelp, bookHelp.contains("fun flowImages("))
    }

    @Test
    fun `整本缓存标记不会被既有清理策略删除`() {
        val src = DiagSourceText.source(bookHelpPath)
        val clearBlock = DiagSourceText.block(
            src, "private fun clearComicCache(book: Book) {", "trimImageCacheSize(imagesDir)"
        )
        assertTrue(clearBlock, clearBlock.contains("val fullCached = isMangaFullCached(book)"))
        assertTrue(clearBlock, clearBlock.contains("if (fullCached)"))
        assertTrue(clearBlock, clearBlock.contains("return"))
        //0 字节文件任何时候都清掉，避免永久挡住重试
        assertTrue(clearBlock, clearBlock.contains("imgFile.length() <= 0"))
        //按章节窗口保留仍然只在用户显式设置保留数时生效
        assertTrue(clearBlock, clearBlock.contains("if (AppConfig.imageRetainNum > 0)"))
        //标记文件放在书目录下而不是 images/ 内
        assertTrue(src, src.contains("private const val mangaFullCacheMarkName"))
        val markBlock = DiagSourceText.block(src, "fun markMangaFullCached(", "fun isMangaFullCached(")
        assertFalse("标记文件不能放进 images 目录", markBlock.contains("cacheImageFolderName"))
    }

    @Test
    fun `日常阅读缓存有容量兜底且只作用于未整本下载的书`() {
        val src = DiagSourceText.source(bookHelpPath)
        //整本缓存提前 return，容量裁剪在它之后
        DiagSourceText.assertOrder(
            src,
            "if (fullCached)",
            "trimImageCacheSize(imagesDir)"
        )
        assertTrue(src, src.contains("private fun trimImageCacheSize(imagesDir: File)"))
        assertTrue(src, src.contains("MANGA_IMAGE_CACHE_LIMIT_BYTES"))
        //纯函数裁剪计划放在统一缓存文件里，便于测试
        val keys = DiagSourceText.source(imageCachePath)
        assertTrue(keys, keys.contains("fun planImageCacheTrim("))
        //启动清理入口不变
        val app = DiagSourceText.source("app/src/main/java/io/legado/app/App.kt")
        assertTrue(app, app.contains("BookHelp.clearInvalidCache()"))
    }

    @Test
    fun `下载接口显式返回成败而不吞异常`() {
        val src = DiagSourceText.source(bookHelpPath)
        val checked = DiagSourceText.block(src, "suspend fun saveImageChecked(", "fun markMangaFullCached(")
        assertTrue(checked, checked.contains("ImageCacheResult.FAILED"))
        //原有 saveImage 语义保持不变（小说/正文插图）
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
