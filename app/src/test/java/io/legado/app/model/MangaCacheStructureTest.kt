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
        assertTrue(keys, keys.contains("fun imageFileName(src: String, suffix: String = MANGA_CACHE_SUFFIX)"))
        assertTrue(keys, keys.contains("fun baseName(src: String)"))
        assertTrue(keys, keys.contains("MD5Utils.md5Encode16(src)"))
        //键保持「绝对地址 MD5」，不改成章节序号
        assertFalse(keys, keys.contains("chapterIndex /"))
        //新旧命名共享同一 basename，换后缀不会变成两份缓存
        assertTrue(keys, keys.contains("fun legacyImageFileName(src: String)"))
        val bookHelp = DiagSourceText.source(bookHelpPath)
        assertTrue(bookHelp, bookHelp.contains("MangaImageKeys.imageFileName(src, suffix)"))
        //清理逻辑必须把新旧两种命名都算进保留集
        assertTrue(bookHelp, bookHelp.contains("MangaImageContent.candidatesFor("))
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
            "download(index,",
            "doneChapters++"
        )
        //章节级只解析一次：不把全书图片一次性收集
        assertEquals(1, DiagSourceText.countOf(src, "BookHelp.ensureChapterImages("))
        assertFalse("不应再一次性收集全书图片", src.contains("allImages"))
    }

    @Test
    fun `下载全部接收每张图片的最终结果`() {
        val src = DiagSourceText.source(mangaCachePath)
        assertTrue(src, src.contains("onResult = onResult"))
        //正文有但没有图片，不能算「这一章下完了」
        assertTrue(src, src.contains("emptyChapters++"))
        //卷节点按已知零图片跳过，不污染失败统计
        assertTrue(src, src.contains("if (chapter.isVolume) {\n            onTotal(0)"))
        //单张失败重试次数有上限
        assertTrue(src, src.contains("retryEach = MangaCache.MAX_RETRY"))
    }

    @Test
    fun `完整缓存标记只在整本且全部图片验证通过时生成`() {
        val src = DiagSourceText.source(mangaCachePath)
        val runBlock = DiagSourceText.block(src, "private suspend fun run()", "private suspend fun downloadChapter")
        assertTrue("没有成功图片不能算成功", runBlock.contains("cachedImages + storedImages == 0 -> State.ALL_FAILED"))
        assertTrue("无图章节不能算成功", runBlock.contains("emptyChapters == 0"))
        assertTrue("失败图片不能算成功", runBlock.contains("failedImages == 0"))
        assertTrue("失败章节不能算成功", runBlock.contains("failedChapters == 0"))
        DiagSourceText.assertOrder(
            runBlock,
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
    fun `统一入口保留旧文件且校验后才算成功`() {
        val src = DiagSourceText.source(imageCachePath)
        val ensureBlock = DiagSourceText.block(src, "private suspend fun ensureLocked(", "private fun pruneLocks()")
        DiagSourceText.assertOrder(
            ensureBlock,
            "if (store.isUsable(key)) return ImageCacheResult.HIT",
            "fetch(key)",
            "isValidData(bytes)",
            //落盘前压缩：结果不允许变大的判断在纯函数里，这里只要求先优化再写
            "optimizeOrOriginal(key, bytes)",
            "store.writeAtomic(key, payload)"
        )
        //错误页/空响应一律不写盘
        assertTrue(ensureBlock, ensureBlock.contains("bytes.isEmpty() || !isValidData(bytes)"))
        //写后回读
        assertTrue(ensureBlock, ensureBlock.contains("if (!store.isUsable(key))"))
        assertFalse("失败路径不能删除旧候选", ensureBlock.contains("store.delete(key)"))
        val bookHelp = DiagSourceText.source(bookHelpPath)
        val fetchBlock = DiagSourceText.block(bookHelp, "private fun imageCache(", "locks = imageLocks")
        DiagSourceText.assertOrder(fetchBlock, "getByteArrayAwait()", "if (book.isImage)", "ImageUtils.decode(src, bytes, isCover = false, bookSource, book)")
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
    fun `阅读页优先本地文件且写入失败复用内存数据`() {
        val src = DiagSourceText.source(mangaVhPath)
        val loadBlock = DiagSourceText.block(src, "fun loadImageWithRetry(", "fun cancelLoad()")
        DiagSourceText.assertOrder(
            loadBlock,
            "BookHelp.ensureImage(",
            "onHitFile = { hitFile = it }",
            "if (result != ImageCacheResult.FAILED)",
            "loadFromCache(",
            "else if (bytes != null)",
            "loadFromBytes("
        )
        //命中后不再有第二次解析/校验：localImageFile 已删除，阅读页不得再调 isImageUsable
        assertFalse("命中路径不得重复校验", src.contains("isImageUsable("))
        //结构检查只验证接线，不替代Android/Glide实际显示测试。
        val memoryBlock = DiagSourceText.block(src, "private fun loadFromBytes(", "private fun loadFromCache(")
        assertTrue(memoryBlock, memoryBlock.contains("ImageLoader.load(context, bytes)"))
        assertTrue(memoryBlock, memoryBlock.contains("DiskCacheStrategy.NONE"))
        assertTrue(memoryBlock, memoryBlock.contains("skipMemoryCache(true)"))
        assertTrue(memoryBlock, memoryBlock.contains("builder.transform(transformation)"))
        assertTrue(memoryBlock, memoryBlock.contains("canDeliverToUi(sequence, imageUrl)"))
        assertFalse("内存回退不能再次请求网络", memoryBlock.contains("loadNetworkFallback("))
        assertFalse("内存回退不能删除旧缓存", memoryBlock.contains(".delete()"))
        val loader = DiagSourceText.source("app/src/main/java/io/legado/app/help/glide/ImageLoader.kt")
        val byteLoader = DiagSourceText.block(loader, "fun load(context: Context, bytes: ByteArray?)", "\n}")
        assertTrue(byteLoader, byteLoader.contains("Glide.with(context).load(bytes)"))
        //显示阶段只读本地文件，不联网
        val cacheBlock = DiagSourceText.block(src, "private fun loadFromCache(", "private fun loadNetworkFallback(")
        assertTrue(cacheBlock, cacheBlock.contains("BookCover.loadMangaLocal(context, file, transformation)"))
        assertFalse("本地显示路径不能直接走网络", cacheBlock.contains("BookCover.loadManga("))
        //命中文件来自统一入口交接；仅在快路径未交接时单次解析兜底
        assertTrue(cacheBlock, cacheBlock.contains("hitFile ?: run"))
        assertTrue(cacheBlock, cacheBlock.contains("BookHelp.getValidatedImageFile(book, imageUrl)"))
        //本地文件被删掉后还有一次网络兜底；兜底经主队列投递，不在监听器回调里直接重入
        assertTrue(cacheBlock, cacheBlock.contains("file.delete()"))
        assertTrue(cacheBlock, cacheBlock.contains("loadNetworkFallback("))
        assertTrue(cacheBlock, cacheBlock.contains("mImage.post"))
        //Glide 的临时缓存不能作为持久化手段
        val coverSrc = DiagSourceText.source("app/src/main/java/io/legado/app/model/BookCover.kt")
        val localLoader = DiagSourceText.block(coverSrc, "fun loadMangaLocal(", "fun preloadManga(")
        assertTrue(localLoader, localLoader.contains("DiskCacheStrategy.NONE"))
        //非图片后缀的文件必须按内容解码：Glide 的 File 模型走 BitmapFactory，不看文件名
        assertTrue(localLoader, localLoader.contains(".load(file)"))
        assertFalse("不能按路径字符串/扩展名分发", localLoader.contains(".load(file.absolutePath)"))
    }

    @Test
    fun `命中路径单次校验并交接文件`() {
        val bookHelp = DiagSourceText.source(bookHelpPath)
        //ensureImage 的命中快路径：先单次解析交接文件，未命中才进入统一入口
        val ensureBlock = DiagSourceText.block(
            bookHelp, "suspend fun ensureImage(", "private fun imageCache("
        )
        DiagSourceText.assertOrder(
            ensureBlock,
            "if (onHitFile != null)",
            "getValidatedImageFile(book, src)?.let",
            "return ImageCacheResult.HIT",
            "imageCache(bookSource, book, chapter).ensure(src, onImageData)"
        )
        //单次解析：候选与旧命名兜底在同一次遍历里完成校验（损坏候选不阻断有效候选）
        val resolverBlock = DiagSourceText.block(
            bookHelp, "fun getValidatedImageFile(", "fun hasImageFile("
        )
        assertTrue(resolverBlock, resolverBlock.contains("MangaImageContent.firstValidImageFile("))
        assertTrue(resolverBlock, resolverBlock.contains("isImageFileValid(file)"))
        //isImageUsable 漫画分支复用同一单次解析，小说路径保持原行为
        val usableBlock = DiagSourceText.block(
            bookHelp, "fun isImageUsable(", "fun isImageFileValid("
        )
        assertTrue(usableBlock, usableBlock.contains("getValidatedImageFile(book, src) != null"))
        assertTrue(usableBlock, usableBlock.contains("isImageFileValid(getImage(book, src))"))
        //预加载探测只做存在性 stat，不得包含任何解码调用
        val probeBlock = DiagSourceText.block(
            bookHelp, "fun hasImageFile(", "    /**\n     * 原子写"
        )
        assertTrue(probeBlock, probeBlock.contains("resolveExistingCacheName("))
        assertFalse("主线程探测不得解码", probeBlock.contains("decodeFile"))
        assertFalse("主线程探测不得解码", probeBlock.contains("BitmapFactory"))
    }

    @Test
    fun `非图片后缀缓存仍按内容读取且兼容旧缓存`() {
        val bookHelp = DiagSourceText.source(bookHelpPath)
        //读取：先找实际存在的文件（新后缀优先，其次旧后缀），找不到才回落到旧命名路径
        val getImageBlock = DiagSourceText.block(
            bookHelp, "fun getImage(book: Book, src: String): File {", "    @Synchronized"
        )
        assertTrue(getImageBlock, getImageBlock.contains("MangaImageContent.resolveExistingCacheName("))
        assertTrue(getImageBlock, getImageBlock.contains("MangaImageKeys.legacyImageFileName(src)"))
        //后缀按内容决定，不按 URL 猜；小说/正文插图继续用 URL 后缀，行为不变
        val writeBlock = DiagSourceText.block(
            bookHelp, "private fun writeImageAtomic(", "private fun deleteStaleVariants("
        )
        assertTrue(
            writeBlock,
            writeBlock.contains("MangaImageContent.suffixFor(bytes, getImageSuffix(src))")
        )
        assertTrue(writeBlock, writeBlock.contains("} else {\n            getImageSuffix(src)"))
        assertTrue(writeBlock, writeBlock.contains("MangaImageKeys.imageFileName(src, suffix)"))
        //漫画不自动删除旧候选；小说保留成功后清理副本的既有行为
        DiagSourceText.assertOrder(bookHelp, "if (written && !book.isImage) {", "deleteStaleVariants(")
        //缓存根目录放 .nomedia：降低被相册/媒体库扫描的概率（不是加密）
        assertTrue(bookHelp, bookHelp.contains("\".nomedia\""))
        //命中判定按文件内容
        assertTrue(bookHelp, bookHelp.contains("BitmapFactory.decodeFile(file.absolutePath, op)"))
        //导出用缓存文件的实际名字，避免 .mcache 导出后找不到资源
        val export = DiagSourceText.source("app/src/main/java/io/legado/app/service/ExportBookService.kt")
        assertTrue(export, export.contains("val fileName = vFile.name"))
    }

    @Test
    fun `压缩只在更小时采用且不做降采样`() {
        val bookHelp = DiagSourceText.source(bookHelpPath)
        val block = DiagSourceText.block(
            bookHelp, "private fun optimizeImageBytes(", "private fun encodeWebp("
        )
        assertTrue(block, block.contains("MangaImageContent.selectSmallerOrOriginal(best, candidate)"))
        //保守回退条件
        assertTrue(block, block.contains("MAX_TRANSCODE_PIXELS"))
        assertTrue(block, block.contains("MangaImageContent.hasMetadataSegments(bytes)"))
        assertTrue(block, block.contains("MangaImageContent.isWebp(bytes)"))
        //动图不能转码（会丢帧）
        assertTrue(block, block.contains("MangaImageContent.isGif(bytes)"))
        //PNG 先无损；老系统没有无损 WebP 就不转
        assertTrue(block, block.contains("Bitmap.CompressFormat.WEBP_LOSSLESS"))
        assertTrue(block, block.contains("isPng && Build.VERSION.SDK_INT < Build.VERSION_CODES.R"))
        assertTrue(block, block.contains("attempts.add("))
        assertTrue(block, block.contains("hasSameDimensions(candidate, width, height)"))
        assertTrue(block, block.contains("OutOfMemoryError"))
        assertTrue(block, block.contains("bitmap?.recycle()"))
        //不降采样、不缩放
        assertFalse("不允许降采样", block.contains("inSampleSize"))
        assertFalse("不允许缩放分辨率", block.contains("createScaledBitmap"))
        //API 30 才有的枚举值必须按版本分流
        assertTrue(bookHelp, bookHelp.contains("private fun lossyWebpFormat()"))
        assertTrue(bookHelp, bookHelp.contains("Bitmap.CompressFormat.WEBP_LOSSY"))
        //接入统一入口
        assertTrue(bookHelp, bookHelp.contains("optimize = { bytes ->"))
        assertTrue(bookHelp, bookHelp.contains("WEBP_LOSSY_QUALITY"))
    }

    @Test
    fun `阅读页在复用时取消在途加载`() {
        val src = DiagSourceText.source(mangaVhPath)
        assertTrue(src, src.contains("fun cancelLoad()"))
        //重绑与复用都取消在途任务，不再使用 cancelChildren 群发取消
        assertTrue(src, src.contains("loadJob?.cancel()"))
        assertFalse(src, src.contains("cancelChildren()"))
        //回主线程的 UI/Glide 操作统一过生命周期守卫
        assertTrue(src, src.contains("private fun canDeliverToUi("))
        assertTrue(src, src.contains("!activity.isDestroyed && !activity.isFinishing"))
        val adapter = DiagSourceText.source(mangaAdapterPath)
        assertTrue(adapter, adapter.contains("vh.cancelLoad()"))
        //宿主销毁后不能再经 Glide.with(activity) 取 RequestManager 清请求
        assertTrue(adapter, adapter.contains("activity.isDestroyed && !activity.isFinishing"))
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
        //清理不能碰正在写入的 .tmp
        assertTrue(src, src.contains("isTransientCacheFile()"))
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
