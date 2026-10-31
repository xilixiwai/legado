package io.legado.app.help.book

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.script.rhino.runScriptWithContext
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ImageBatchStats
import io.legado.app.model.ImageCacheResult
import io.legado.app.model.ImageFileInfo
import io.legado.app.model.ImageStore
import io.legado.app.model.MangaImageContent
import io.legado.app.model.MangaImageExtractor
import io.legado.app.model.MangaImageKeys
import io.legado.app.model.PersistentImageCache
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.ensureImageBatch
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.planImageCacheTrim
import io.legado.app.model.retryDownload
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.exists
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.JaccardSimilarity
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Suppress("unused", "ConstPropertyName")
object BookHelp {
    private val downloadDir: File = appCtx.externalFiles
    private const val cacheFolderName = "book_cache"
    private const val cacheImageFolderName = "images"
    private const val cacheEpubFolderName = "epub"

    /** 整本漫画下载完成的标记文件名，放在书目录下（不在 images/ 内，不会被图片清理遍历到） */
    private const val mangaFullCacheMarkName = "manga_full_cache"
    private val downloadImages = ConcurrentHashMap<String, Mutex>()

    /**
     * 统一图片缓存使用的去重锁表。
     *
     * 阅读页、预下载、整本下载可能同时请求同一张图，共享同一张表才能只下载一次。
     */
    private val imageLocks = ConcurrentHashMap<String, Mutex>()

    /** 预下载的 fire-and-forget 作用域（不绑定页面生命周期，页面上滑走也要落盘） */
    private val prefetchScope = CoroutineScope(SupervisorJob() + IO)

    /** 预下载并发：低于阅读页可见图片数，避免和前台抢带宽 */
    private val prefetchPermits = Semaphore(2)

    /** 在途的预下载地址，防止同一张图被预加载器重复触发（值为占位符） */
    private val prefetching = ConcurrentHashMap<String, Boolean>()

    private const val PREFETCH_RETRY = 1

    /** 单本漫画的日常阅读缓存上限；超出后按最久未修改优先清理（整本下载的书不受影响） */
    private const val MANGA_IMAGE_CACHE_LIMIT_BYTES = 512L * 1024 * 1024

    /**
     * 允许转码的最大像素数（约 800 万像素 ≈ 32MB ARGB_8888）。
     *
     * 超长条漫/超大图在低内存设备上解码就会 OOM，直接保留原始字节；
     * 这里是**保护阈值**，不是降采样：低于阈值的图片仍按原始分辨率编码。
     */
    private const val MAX_TRANSCODE_PIXELS = 8_000_000L

    /**
     * 同时进行的转码数上限。
     *
     * 下载并发是 4，但每张图要解码成整张 Bitmap，4 张 8MP 同时解码 ≈ 128MB，
     * 低端机会被 LMK 干掉。限制为 2，峰值约 64MB，代价只是编码排队而不是重新下载。
     */
    private val optimizePermits = Semaphore(2)

    /**
     * WebP 有损编码质量。
     *
     * 参数不是拍脑袋定的：用 Pillow/libwebp 对仓库内可用样本实测（见交付报告），
     * q95 对已经是 JPEG 的图**普遍更大**（+64%~+90%），q90 才开始有可比性，
     * 而 q90 对线稿页 PSNR 仍有 ~56dB。取 90 是「能省则省、省不了就留原图」的折中。
     */
    private const val WEBP_LOSSY_QUALITY = 90

    /** 小于该体积的图片压缩收益极低，跳过解码以省一次峰值内存 */
    private const val MIN_TRANSCODE_BYTES = 16 * 1024

    val cachePath = FileUtils.getPath(downloadDir, cacheFolderName)

    fun clearCache() {
        FileUtils.delete(
            FileUtils.getPath(downloadDir, cacheFolderName)
        )
    }

    fun clearCache(book: Book) {
        val filePath = FileUtils.getPath(downloadDir, cacheFolderName, book.getFolderName())
        FileUtils.delete(filePath)
    }

    /**
     * 把书籍缓存目录迁移到新地址对应的目录名。
     *
     * @return 是否真的移动了目录。书籍没有缓存目录（从未下载/缓存过）时返回 false，
     * 调用方据此区分「改了名」和「本来就没有目录」，避免把没有缓存的书也统计成改名成功。
     */
    fun updateCacheFolder(oldBook: Book, newBook: Book): Boolean {
        val oldFolderName = oldBook.getFolderNameNoCache()
        val newFolderName = newBook.getFolderNameNoCache()
        if (oldFolderName == newFolderName) return false
        val oldFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            oldFolderName
        )
        val newFolderPath = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            newFolderName
        )
        return FileUtils.move(oldFolderPath, newFolderPath)
    }

    /**
     * 清除已删除书的缓存 解压缓存
     */
    suspend fun clearInvalidCache() {
        withContext(IO) {
            val bookFolderNames = hashSetOf<String>()
            val originNames = hashSetOf<String>()
            appDb.bookDao.all.forEach {
                clearComicCache(it)
                bookFolderNames.add(it.getFolderName())
                if (it.isEpub) originNames.add(it.originName)
            }
            downloadDir.getFile(cacheFolderName)
                .listFiles()?.forEach { bookFile ->
                    if (!bookFolderNames.contains(bookFile.name)) {
                        FileUtils.delete(bookFile.absolutePath)
                    }
                }
            downloadDir.getFile(cacheEpubFolderName)
                .listFiles()?.forEach { epubFile ->
                    if (!originNames.contains(epubFile.name)) {
                        FileUtils.delete(epubFile.absolutePath)
                    }
                }
            FileUtils.delete(ArchiveUtils.TEMP_PATH)
            val filesDir = appCtx.filesDir
            FileUtils.delete("$filesDir/shareBookSource.json")
            FileUtils.delete("$filesDir/shareRssSource.json")
            FileUtils.delete("$filesDir/books.json")
        }
    }

    //清除已经看过的漫画数据
    private fun clearComicCache(book: Book) {
        //只处理漫画
        if (!book.isImage) {
            return
        }
        //整本下载过的漫画按用户显式意图保留，不受「只保留当前章节附近图片」影响
        val fullCached = isMangaFullCached(book)
        //0 字节/半截文件任何时候都该清掉，否则会永久挡住重试
        val imagesDir = downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        )
        imagesDir.listFiles()?.forEach { imgFile ->
            //.tmp 是正在写入的临时文件，长度可能瞬间为 0，不能当坏文件清掉
            if (!imgFile.isTransientCacheFile() && imgFile.length() <= 0) imgFile.delete()
        }
        //为0的时候，不清除已缓存数据
        if (fullCached) {
            return
        }
        if (AppConfig.imageRetainNum > 0) {
            //向前保留设定数量，向后保留预下载数量
            val startIndex = book.durChapterIndex - AppConfig.imageRetainNum
            val endIndex = book.durChapterIndex + AppConfig.preDownloadNum
            val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl, startIndex, endIndex)
            val imgNames = hashSetOf<String>()
            //获取需要保留章节的图片信息
            chapterList.forEach {
                val content = getContent(book, it)
                if (content != null) {
                    extractMangaImageUrls(it, content).forEach { mSrc ->
                        //新旧命名都要留在保留集里，否则升级后第一次清理就会把旧缓存全删掉
                        imgNames.addAll(
                            MangaImageContent.candidatesFor(
                                MangaImageKeys.baseName(mSrc),
                                getImageSuffix(mSrc)
                            )
                        )
                    }
                }
            }
            imagesDir.listFiles()?.forEach { imgFile ->
                if (!imgFile.isTransientCacheFile() && !imgNames.contains(imgFile.name)) {
                    imgFile.delete()
                }
            }
        }
        //容量兜底：日常阅读会把图片越存越多，超过单本上限时按最久未用优先清理
        trimImageCacheSize(imagesDir)
    }

    /** 正在写入的临时文件（`.tmp`），任何清理都不能碰，否则会打断进行中的落盘 */
    private fun File.isTransientCacheFile(): Boolean = name.endsWith(".tmp")

    /**
     * 单本漫画图片目录的容量兜底。
     *
     * 只对「未整本下载」的书生效（调用方已跳过整本缓存），
     * 避免日常阅读无限占盘，又不动用户显式下载的内容。
     */
    private fun trimImageCacheSize(imagesDir: File) {
        val files = imagesDir.listFiles()?.filter { it.isFile && !it.isTransientCacheFile() } ?: return
        if (files.isEmpty()) return
        val victims = planImageCacheTrim(
            files.map { ImageFileInfo(it.name, it.length(), it.lastModified()) },
            MANGA_IMAGE_CACHE_LIMIT_BYTES
        )
        victims.forEach { File(imagesDir, it).delete() }
    }

    suspend fun saveContent(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        try {
            saveText(book, bookChapter, content)
            //saveImages(bookSource, book, bookChapter, content)
            postEvent(EventBus.SAVE_CONTENT, Pair(book, bookChapter))
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("保存正文失败 ${book.name} ${bookChapter.title}", e)
        }
    }

    fun saveText(
        book: Book,
        bookChapter: BookChapter,
        content: String
    ) {
        if (content.isEmpty()) return
        //保存文本
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName(),
        ).writeText(content)
        if (book.isOnLineTxt && AppConfig.tocCountWords) {
            val wordCount = StringUtils.wordCountFormat(content.length)
            bookChapter.wordCount = wordCount
            appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, wordCount)
        }
    }

    fun flowImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            val matcher = AppPattern.imgPattern.matcher(content)
            while (matcher.find()) {
                val src = matcher.group(1) ?: continue
                val mSrc = NetworkUtils.getAbsoluteURL(bookChapter.url, src)
                emit(mSrc)
            }
        }
    }

    /**
     * 漫画专用的图片地址提取。
     *
     * 与 [flowImages] 的差异只有一处，但很关键：**懒加载属性优先**。
     *
     * 很多漫画站的标签形如
     * `<img class="lazy" data-src="/p/2.jpg" src="/static/loading.gif">`。
     * 既有 `imgPattern` 的 `[^>]*src=` 会匹配到最右边的 `src=`，
     * 也就是占位图 `loading.gif`；整章所有地址因此变成同一个，
     * 再被调用方的 `distinctUntilChanged()` 折叠成 1 张 —— 表现为
     * 「一章几百张图，只下载/只显示第一张」。
     *
     * 这里按 `data-src` → `data-original` → `data-url` → `src` 的优先级取值，
     * 标签里没有懒加载属性时（普通 `<img src>`）输出与 [flowImages] 完全一致，
     * 不会让既有缓存失效。
     */
    fun flowMangaImages(bookChapter: BookChapter, content: String): Flow<String> {
        return flow {
            extractMangaImageUrls(bookChapter, content).forEach { emit(it) }
        }
    }

    /** [flowMangaImages] 的非挂起版本，供清理逻辑等同步场景复用同一套提取规则 */
    fun extractMangaImageUrls(bookChapter: BookChapter, content: String): List<String> {
        return MangaImageExtractor.extract(bookChapter.url, content)
    }

    /**
     * 当前章节的图片地址列表（只折叠**相邻**重复，与阅读页分页语义一致）。
     *
     * 与 [extractMangaImageUrls] 同一份规则：读取与下载共用，避免
     * 「下载用的键」和「显示用的键」对不上。
     */
    fun chapterImageUrls(bookChapter: BookChapter, content: String): List<String> {
        return MangaImageExtractor.collapseConsecutive(
            extractMangaImageUrls(bookChapter, content)
        )
    }

    suspend fun saveImages(
        bookSource: BookSource,
        book: Book,
        bookChapter: BookChapter,
        content: String,
        concurrency: Int = AppConfig.threadCount
    ) = coroutineScope {
        flowImages(bookChapter, content).onEachParallel(concurrency) { mSrc ->
            saveImage(bookSource, book, mSrc, bookChapter)
        }.collect()
    }

    suspend fun saveImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ) {
        if (isImageExist(book, src)) {
            return
        }
        val mutex = synchronized(this) {
            downloadImages.getOrPut(src) { Mutex() }
        }
        mutex.lock()
        try {
            if (isImageExist(book, src)) {
                return
            }
            val analyzeUrl = AnalyzeUrl(
                src, source = bookSource, coroutineContext = coroutineContext
            )
            val bytes = analyzeUrl.getByteArrayAwait()
            //某些图片被加密，需要进一步解密
            runScriptWithContext {
                ImageUtils.decode(
                    src, bytes, isCover = false, bookSource, book
                )
            }?.let {
                if (!checkImage(it)) {
                    // 如果部分图片失效，每次进入正文都会花很长时间再次获取图片数据
                    // 所以无论如何都要将数据写入到文件里
                    // throw NoStackTraceException("数据异常")
                    AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载错误 数据异常")
                }
                writeImage(book, src, it)
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val msg = "${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}"
            AppLog.put(msg, e)
        } finally {
            downloadImages.remove(src)
            mutex.unlock()
        }
    }

    private fun imageDir(book: Book): File {
        return downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            cacheImageFolderName
        )
    }

    /**
     * 图片缓存文件。
     *
     * 漫画逐个验证候选，小说保留存在性查找：先找 `.mcache`/`.svg`，再找旧版 URL 后缀，
     * 都没有时返回旧命名路径（`ImageProvider` 等小说侧调用方原有行为不变）。
     * 新旧命名共用同一个 MD5 前缀，读取不会改名、解密或删除历史候选。
     */
    fun getImage(book: Book, src: String): File {
        val dir = imageDir(book)
        val base = MangaImageKeys.baseName(src)
        val existing = MangaImageContent.resolveExistingCacheName(
            baseName = base,
            urlSuffix = getImageSuffix(src),
            exists = { name ->
                val file = File(dir, name)
                if (book.isImage) isImageFileValid(file) else file.exists()
            }
        )
        return File(dir, existing ?: MangaImageKeys.legacyImageFileName(src))
    }

    @Synchronized
    fun writeImage(book: Book, src: String, bytes: ByteArray) {
        writeImageAtomic(book, src, bytes)
    }

    @Synchronized
    fun isImageExist(book: Book, src: String): Boolean {
        return getImage(book, src).exists()
    }

    /**
     * 图片文件存在、非空**且能解码成图片**。
     *
     * 只看 `exists()` 会把「403 页面」「验证页 HTML」「0 字节半截文件」当成已缓存，
     * 于是既不重下也显示不出来。命中判定、跳过判定、清理判定统一以本方法为准。
     * 判定依据是**文件内容**（`BitmapFactory` 按 magic bytes 识别），与扩展名无关。
     */
    fun isImageUsable(book: Book, src: String): Boolean = isImageFileValid(getImage(book, src))

    fun isImageFileValid(file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return kotlin.runCatching {
            val op = BitmapFactory.Options()
            op.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, op)
            if (op.outWidth >= 1 && op.outHeight >= 1) {
                //只读尺寸无法发现部分截断图片；缩小解码用于验证，不改变保存或显示的图片。
                val decodeOptions = BitmapFactory.Options()
                var sample = 1
                while (max(op.outWidth, op.outHeight) / sample > 512) sample *= 2
                decodeOptions.inSampleSize = sample
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                val valid = bitmap != null
                bitmap?.recycle()
                valid
            } else {
                //SVG 等非位图格式交给 SvgUtils 判尺寸
                SvgUtils.getSize(file.absolutePath) != null
            }
        }.getOrDefault(false)
    }

    /**
     * 原子写：先写 `.tmp` 再改名，避免中断留下半截文件被当成命中。
     * 写完校验候选文件，覆盖式改名不受支持时保留旧文件并报告失败。
     *
     * 后缀按**内容**决定（`MangaImageContent.suffixFor`）：
     * - 漫画：已知位图统一 `.<mcache>`，SVG 保持 `.svg`；
     * - 小说/正文插图：沿用 URL 后缀，行为与升级前完全一致。
     *
     * 漫画读取时不自动删除旧后缀候选；小说保留原有成功后清理副本的行为。
     */
    @Synchronized
    private fun writeImageAtomic(book: Book, src: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val dir = imageDir(book)
        if (!dir.exists() && !dir.mkdirs()) return false
        ensureNoMedia(dir)
        val suffix = if (book.isImage) {
            MangaImageContent.suffixFor(bytes, getImageSuffix(src))
        } else {
            getImageSuffix(src)
        }
        val target = File(dir, MangaImageKeys.imageFileName(src, suffix))
        val written = try {
            writeImageCandidate(target, bytes, validate = { candidate ->
                //小说失效插图仍按原行为保存响应，避免每次进入正文反复请求。
                !book.isImage || isImageFileValid(candidate)
            })
        } catch (e: Exception) {
            AppLog.put("${book.name} 图片 $src 写入缓存失败\n${e.localizedMessage}", e)
            false
        }
        if (written && !book.isImage) {
            //新文件已校验落盘，清掉同一张图的旧后缀副本，避免一份图两份空间
            deleteStaleVariants(dir, src, keepName = target.name)
        }
        return written
    }

    /** 同一 MD5 前缀下、除保留文件外的旧后缀副本 */
    private fun deleteStaleVariants(dir: File, src: String, keepName: String) {
        MangaImageContent.candidatesFor(
            MangaImageKeys.baseName(src),
            getImageSuffix(src)
        ).forEach { name ->
            if (name == keepName) return@forEach
            val stale = File(dir, name)
            if (stale.exists() && !stale.delete()) {
                AppLog.put("旧缓存文件 ${stale.name} 未能删除")
            }
        }
    }

    /**
     * 在**书目录**（`images/` 的上一层）放 `.nomedia`，让媒体扫描跳过这本书的图片目录。
     *
     * 不能放在 `book_cache/` 根目录：`clearInvalidCache` 会把根目录下「不是书目录名」的条目
     * 当作残留删掉，`.nomedia` 每次启动都会被清掉。放在书目录内既不会被清理，
     * 又覆盖其下的 `images/`。
     *
     * 注意：这只是减少被相册/媒体库扫描到的概率，**不是加密**，
     * 专业工具仍能读出内容；缓存目录本身也已在 App 专属外部目录内。
     */
    private fun ensureNoMedia(imagesDir: File) {
        val bookCacheDir = imagesDir.parentFile ?: return
        val marker = File(bookCacheDir, ".nomedia")
        if (!marker.exists()) {
            runCatching { marker.createNewFile() }
        }
    }

    /**
     * 落盘前的图片压缩：PNG 走无损 WebP，JPEG 走高质量有损 WebP，
     * 只有「更小且尺寸不变」才采用。
     *
     * 哪些图**不转码**（任一命中就保留原图，绝不为了统一格式破坏图片）：
     * - SVG：没有位图编码器；WebP/已经符合目标格式：不重复转码；
     * - GIF：可能是动图，转码会丢帧；BMP 等罕见格式不做猜测；
     * - 带 EXIF/ICC 元数据：转码会丢方向与色彩配置；
     * - PNG 且系统 < API 30：拿不到无损 WebP，转有损会把文字糊掉；
     * - 像素数超过 [MAX_TRANSCODE_PIXELS]：低内存设备解码就 OOM；
     * - 体积过小、解码失败、编码失败、内存不足。
     *
     * 不做降采样：`inSampleSize` 从不设置，分辨率保持不变。
     */
    private fun optimizeImageBytes(bytes: ByteArray): ByteArray {
        if (bytes.size < MIN_TRANSCODE_BYTES) return bytes
        if (MangaImageContent.isSvg(bytes)) return bytes
        if (MangaImageContent.isWebp(bytes)) return bytes
        if (MangaImageContent.isGif(bytes)) return bytes
        val isPng = MangaImageContent.isPng(bytes)
        val isJpeg = MangaImageContent.isJpeg(bytes)
        if (!isPng && !isJpeg) return bytes
        if (MangaImageContent.hasMetadataSegments(bytes)) return bytes
        //PNG 无损 WebP 需要 API 30；老系统上转有损会明显糊字，干脆不转
        if (isPng && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < 1 || height < 1) return bytes
        if (width.toLong() * height > MAX_TRANSCODE_PIXELS) return bytes
        var bitmap: Bitmap? = null
        return try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return bytes
            //PNG 先试无损（线稿/文字零损失），只有在无损没变小的时候才退到有损
            val attempts = ArrayList<Pair<Bitmap.CompressFormat, Int>>(2)
            if (isPng) attempts.add(Bitmap.CompressFormat.WEBP_LOSSLESS to 100)
            attempts.add(lossyWebpFormat() to WEBP_LOSSY_QUALITY)
            var best = bytes
            for ((format, quality) in attempts) {
                val candidate = encodeWebp(bitmap, format, quality) ?: continue
                if (!hasSameDimensions(candidate, width, height)) {
                    //编码后尺寸变了说明这条路径不可信，跳过该次结果
                    continue
                }
                if (candidate.size < best.size) {
                    //「不放大」的唯一判据在纯函数里，这里只负责挑第一次变小的结果
                    val merged = MangaImageContent.selectSmallerOrOriginal(best, candidate)
                    if (merged !== best) {
                        best = merged
                        //已经拿到更小的结果就停：无损优先，避免为几 KB 再压一次
                        break
                    }
                }
            }
            best
        } catch (e: OutOfMemoryError) {
            AppLog.put("漫画图片压缩内存不足，保留原图")
            bytes
        } catch (e: Exception) {
            AppLog.put("漫画图片压缩失败，保留原图\n${e.localizedMessage}")
            bytes
        } finally {
            bitmap?.recycle()
        }
    }

    /** 编码成 WebP；失败返回 null（调用方按原图处理） */
    private fun encodeWebp(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): ByteArray? {
        val out = ByteArrayOutputStream(bitmap.byteCount / 4)
        if (!bitmap.compress(format, quality, out)) return null
        return out.toByteArray()
    }

    /**
     * 有损 WebP 的格式常量。
     *
     * `WEBP_LOSSY` 是 API 30 才有的枚举值，老系统上引用会在运行期抛 `NoSuchFieldError`，
     * 所以必须按系统版本分流，不能直接写死。
     */
    @Suppress("DEPRECATION")
    private fun lossyWebpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /** 编码后必须仍是同一分辨率 */
    private fun hasSameDimensions(bytes: ByteArray, width: Int, height: Int): Boolean {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op) }
        return op.outWidth == width && op.outHeight == height
    }

    /**
     * 统一图片缓存入口（**唯一**写持久化目录的地方，供三个场景共用）：
     * 阅读页、预下载、整本下载。
     *
     * 契约见 [PersistentImageCache]：本地可用即命中且不联网，失败保留旧候选；
     * 只有「有效图片 + 原子写入 + 回读校验」全部通过才返回
     * [ImageCacheResult.STORED]/[ImageCacheResult.HIT]，否则 [ImageCacheResult.FAILED]。
     */
    suspend fun ensureImage(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null,
        onImageData: ((ByteArray) -> Unit)? = null,
    ): ImageCacheResult {
        if (src.isBlank()) return ImageCacheResult.FAILED
        return imageCache(bookSource, book, chapter).ensure(src, onImageData)
    }

    /**
     * 处理一整章的全部图片（逐张走 [ensureImage]）。
     *
     * @param concurrency 在途请求上限（整本下载 4 路，预下载 2 路，章节缓存 1 路）
     * @param retryEach 单张图片失败后的重试次数（不含首次）
     * @param onTotal 图片地址解析完成后的总数（章节进度分母）
     * @param onProgress 已处理张数（成功、跳过、失败都算已处理）
     */
    suspend fun ensureChapterImages(
        bookSource: BookSource?,
        book: Book,
        chapter: BookChapter,
        content: String,
        concurrency: Int,
        retryEach: Int = 0,
        onTotal: (Int) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): ImageBatchStats {
        val urls = chapterImageUrls(chapter, content)
        onTotal(urls.size)
        if (urls.isEmpty()) return ImageBatchStats(0)
        val cache = imageCache(bookSource, book, chapter)
        return ensureImageBatch(
            urls = urls,
            concurrency = concurrency,
            onProgress = onProgress,
            ensure = { url -> ensureWithRetry(cache, url, retryEach) },
        )
    }

    /**
     * 单张图片按统一入口重试。
     *
     * 每次重新检查候选，失败时保留旧文件；不可用的候选不会阻止重新获取。
     */
    private suspend fun ensureWithRetry(
        cache: PersistentImageCache,
        src: String,
        retry: Int,
    ): ImageCacheResult {
        var result = ImageCacheResult.FAILED
        retryDownload(retry) {
            result = cache.ensure(src)
            result != ImageCacheResult.FAILED
        }
        return result
    }

    /**
     * 预下载一张图：fire-and-forget，不阻塞 UI。
     *
     * 预加载器（`RecyclerViewPreloader`）原本只用 Glide 预热临时缓存，
     * 换 IP/清缓存后等于没下过；这里改为真正落盘。
     */
    fun prefetchImage(bookSource: BookSource?, book: Book, src: String) {
        if (src.isBlank()) return
        if (prefetching.putIfAbsent(src, true) != null) return
        prefetchScope.launch {
            try {
                prefetchPermits.withPermit {
                    var attempt = 0
                    while (true) {
                        if (ensureImage(bookSource, book, src) != ImageCacheResult.FAILED) {
                            return@withPermit
                        }
                        if (attempt++ >= PREFETCH_RETRY) return@withPermit
                        kotlinx.coroutines.delay(1000L * attempt)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("漫画图片预下载失败\n${e.localizedMessage}")
            } finally {
                prefetching.remove(src)
            }
        }
    }

    private fun imageCache(
        bookSource: BookSource?,
        book: Book,
        chapter: BookChapter? = null,
    ) = PersistentImageCache(
        store = BookImageStore(book),
        isValidData = { checkImage(it) },
        fetch = { src ->
            val bytes = AnalyzeUrl(
                src, source = bookSource, coroutineContext = currentCoroutineContext()
            ).getByteArrayAwait()
            //只处理新网络字节，命中历史缓存不会再次执行书源解密。
            if (book.isImage) {
                runScriptWithContext {
                    ImageUtils.decode(src, bytes, isCover = false, bookSource, book)
                }
            } else {
                bytes
            }
        },
        locks = imageLocks,
        onInvalidData = { src ->
            AppLog.put("${book.name} ${chapter?.title} 图片 $src 数据异常，未写入缓存")
        },
        onFetchFailed = { src, e ->
            AppLog.put("${book.name} ${chapter?.title} 图片 $src 下载失败\n${e.localizedMessage}", e)
        },
        onOptimizeFailed = { src, e ->
            AppLog.put("${book.name} ${chapter?.title} 图片 $src 压缩失败，保留原图\n${e.localizedMessage}")
        },
        //压缩只在成功下载后执行；命中本地缓存时根本不会走到这里
        optimize = { bytes ->
            withContext(IO) { optimizePermits.withPermit { optimizeImageBytes(bytes) } }
        },
    )

    /**
     * [PersistentImageCache] 的 Android 侧存储实现。
     *
     * 删除坏文件时顺带撤销整本标记：标记只能代表「已验证的完整结果」，
     * 一旦发现图不可用就不再成立。
     */
    private class BookImageStore(private val book: Book) : ImageStore {

        override fun exists(key: String): Boolean = BookHelp.getImage(book, key).exists()

        override fun isUsable(key: String): Boolean = BookHelp.isImageUsable(book, key)

        override fun delete(key: String) {
            val file = BookHelp.getImage(book, key)
            if (!file.exists()) return
            if (file.delete() && BookHelp.isMangaFullCached(book)) {
                BookHelp.unmarkMangaFullCached(book)
            }
        }

        override fun writeAtomic(key: String, bytes: ByteArray): Boolean =
            BookHelp.writeImageAtomic(book, key, bytes)
    }

    /**
     * 兼容旧调用方（整本下载、结构测试）的布尔版本。
     * @return true 表示本地已有可用文件或本次成功落盘
     */
    suspend fun saveImageChecked(
        bookSource: BookSource?,
        book: Book,
        src: String,
        chapter: BookChapter? = null
    ): Boolean {
        return ensureImage(bookSource, book, src, chapter) != ImageCacheResult.FAILED
    }

    /**
     * 标记该书已完成「整本漫画下载」。
     *
     * 用缓存目录里的一个空标记文件实现，不新增数据库字段，也不影响其它模块。
     * [clearComicCache] 见到标记就跳过，避免用户设置了「图片保留章节数」后
     * 整本下载的图片在下次启动被批量删除。
     */
    fun markMangaFullCached(book: Book) {
        runCatching {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                mangaFullCacheMarkName
            )
        }
    }

    fun isMangaFullCached(book: Book): Boolean {
        return downloadDir.exists(
            cacheFolderName,
            book.getFolderName(),
            mangaFullCacheMarkName
        )
    }

    /**
     * 撤销整本标记。
     *
     * 标记只代表「已验证的完整结果」：一旦发现某张图不可用（坏文件被清），
     * 或者用户取消/部分失败，都必须撤销，否则清理逻辑会把它当成受保护内容。
     */
    fun unmarkMangaFullCached(book: Book) {
        runCatching {
            downloadDir.getFile(
                cacheFolderName,
                book.getFolderName(),
                mangaFullCacheMarkName
            ).delete()
        }
    }

    fun getImageSuffix(src: String): String {
        return UrlUtil.getSuffix(src, "jpg")
    }

    @Throws(IOException::class, FileNotFoundException::class)
    fun getEpubFile(book: Book): ZipFile {
        val uri = book.getLocalUri()
        if (uri.isContentScheme()) {
            FileUtils.createFolderIfNotExist(downloadDir, cacheEpubFolderName)
            val path = FileUtils.getPath(downloadDir, cacheEpubFolderName, book.originName)
            val file = File(path)
            val doc = DocumentFile.fromSingleUri(appCtx, uri)
                ?: throw IOException("文件不存在")
            if (!file.exists() || doc.lastModified() > book.latestChapterTime) {
                LocalBook.getBookInputStream(book).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
            return ZipFile(file)
        }
        return ZipFile(uri.path)
    }

    /**
     * 获取本地书籍文件的ParcelFileDescriptor
     *
     * @param book
     * @return
     */
    @Throws(IOException::class, FileNotFoundException::class)
    fun getBookPFD(book: Book): ParcelFileDescriptor? {
        val uri = book.getLocalUri()
        return if (uri.isContentScheme()) {
            appCtx.contentResolver.openFileDescriptor(uri, "r")
        } else {
            ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    fun getChapterFiles(book: Book): HashSet<String> {
        val fileNames = hashSetOf<String>()
        if (book.isLocalTxt) {
            return fileNames
        }
        FileUtils.createFolderIfNotExist(
            downloadDir,
            subDirs = arrayOf(cacheFolderName, book.getFolderName())
        ).list()?.let {
            fileNames.addAll(it)
        }
        return fileNames
    }

    /**
     * 检测该章节是否下载
     */
    fun hasContent(book: Book, bookChapter: BookChapter): Boolean {
        return if (book.isLocalTxt ||
            (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title))
        ) {
            true
        } else {
            downloadDir.exists(
                cacheFolderName,
                book.getFolderName(),
                bookChapter.getFileName()
            )
        }
    }

    /**
     * 检测图片是否下载
     *
     * 与写入侧口径必须一致：
     * - 地址用 [extractMangaImageUrls] 的绝对化结果（旧实现直接用原文 src，
     *   正文里是相对地址时永远对不上，于是每轮缓存都重复下载图片）；
     * - 命中判定用 [isImageUsable]（可解码），而不是 `exists()`。
     */
    fun hasImageContent(book: Book, bookChapter: BookChapter): Boolean {
        if (!hasContent(book, bookChapter)) {
            return false
        }
        val content = getContent(book, bookChapter) ?: return false
        val urls = extractMangaImageUrls(bookChapter, content)
        if (urls.isEmpty()) return false
        return urls.all { isImageUsable(book, it) }
    }

    private fun checkImage(bytes: ByteArray): Boolean {
        val op = BitmapFactory.Options()
        op.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        if (op.outWidth < 1 && op.outHeight < 1) {
            return SvgUtils.getSize(ByteArrayInputStream(bytes)) != null
        }
        return true
    }

    /**
     * 读取章节内容
     */
    fun getContent(book: Book, bookChapter: BookChapter): String? {
        val file = downloadDir.getFile(
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        )
        if (file.exists()) {
            val string = file.readText()
            if (string.isEmpty()) {
                return null
            }
            return string
        }
        if (book.isLocal) {
            val string = LocalBook.getContent(book, bookChapter)
            if (string != null && book.isEpub) {
                saveText(book, bookChapter, string)
            }
            return string
        }
        return null
    }

    /**
     * 删除章节内容
     */
    fun delContent(book: Book, bookChapter: BookChapter) {
        FileUtils.createFileIfNotExist(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName()
        ).delete()
    }

    /**
     * 设置是否禁用正文的去除重复标题,针对单个章节
     */
    fun setRemoveSameTitle(book: Book, bookChapter: BookChapter, removeSameTitle: Boolean) {
        val fileName = bookChapter.getFileName("nr")
        val contentProcessor = ContentProcessor.get(book)
        if (removeSameTitle) {
            val path = FileUtils.getPath(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.remove(fileName)
            File(path).delete()
        } else {
            FileUtils.createFileIfNotExist(
                downloadDir,
                cacheFolderName,
                book.getFolderName(),
                fileName
            )
            contentProcessor.removeSameTitleCache.add(fileName)
        }
    }

    /**
     * 获取是否去除重复标题
     */
    fun removeSameTitle(book: Book, bookChapter: BookChapter): Boolean {
        val path = FileUtils.getPath(
            downloadDir,
            cacheFolderName,
            book.getFolderName(),
            bookChapter.getFileName("nr")
        )
        return !File(path).exists()
    }

    /**
     * 格式化书名
     */
    fun formatBookName(name: String): String {
        return name
            .replace(AppPattern.nameRegex, "")
            .trim { it <= ' ' }
    }

    /**
     * 格式化作者
     */
    fun formatBookAuthor(author: String): String {
        return author
            .replace(AppPattern.authorRegex, "")
            .trim { it <= ' ' }
    }

    private val jaccardSimilarity by lazy {
        JaccardSimilarity()
    }

    /**
     * 根据目录名获取当前章节
     */
    fun getDurChapter(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = getChapterNum(oldDurChapterName)
        val oldName = getPureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = getPureChapterName(newChapterList[i].title)
                val temp = jaccardSimilarity.apply(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = getChapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    fun getDurChapter(
        oldBook: Book,
        newChapterList: List<BookChapter>
    ): Int {
        return oldBook.run {
            getDurChapter(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

    private val chapterNamePattern1 by lazy {
        Pattern.compile(
            ".*?第([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话]"
        )
    }

    @Suppress("RegExpSimplifiable")
    private val chapterNamePattern2 by lazy {
        Pattern.compile(
            "^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*([\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、]|\\.[^\\d])"
        )
    }

    private val regexA by lazy {
        return@lazy "\\s".toRegex()
    }

    private fun getChapterNum(chapterName: String?): Int {
        chapterName ?: return -1
        val chapterName1 = StringUtils.fullToHalf(chapterName).replace(regexA, "")
        return StringUtils.stringToInt(
            (
                    chapterNamePattern1.matcher(chapterName1).takeIf { it.find() }
                        ?: chapterNamePattern2.matcher(chapterName1).takeIf { it.find() }
                    )?.group(1)
                ?: "-1"
        )
    }

    private val regexOther by lazy {
        // 所有非字母数字中日韩文字 CJK区+扩展A-F区
        @Suppress("RegExpDuplicateCharacterInClass")
        return@lazy "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
        return@lazy "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    private val regexC by lazy {
        //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
        return@lazy "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    private fun getPureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }

}
