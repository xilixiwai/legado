package io.legado.app.model

import io.legado.app.data.entities.Book
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

/**
 * 漫画图片持久化缓存的**统一核心**。
 *
 * 三个入口共用同一套语义：
 * - 日常阅读（[io.legado.app.ui.book.manga.recyclerview.MangaVH]）
 * - 预下载（阅读页预加载器触发的 [io.legado.app.help.book.BookHelp.prefetchImage]）
 * - 下载全部（[MangaCache]）
 *
 * 契约（与 UI 无关，可在 JVM 上直接测试）：
 * 1. 本地文件「存在且可解码」才算命中，命中不发任何网络请求；
 * 2. 本地候选不可用时尝试下载，下载或写入失败不得预先删除旧文件；
 * 3. 下载结果必须是有效图片（非空、可解码），否则**不写盘**；
 * 4. 写盘是原子的（临时文件 + rename），写后回读校验；
 * 5. 只有落盘且校验通过，才返回 [ImageCacheResult.STORED]。
 *
 * 类本身不持有 Android 依赖：存储、校验、下载都以参数注入，
 * 便于用假实现覆盖「错误页/空响应/写盘失败/取消」等真实环境难以构造的场景。
 */
enum class ImageCacheResult {
    /** 本地已有可用文件，未发网络请求 */
    HIT,

    /** 本次下载并成功落盘 */
    STORED,

    /** 获取或校验失败，本地无可用文件 */
    FAILED,
}

/**
 * 图片文件的存储抽象。
 *
 * 键是「图片绝对地址」（见 [MangaImageKeys.imageFileName]），
 * 不是章节序号 —— 换源后不同源的图片地址不同，天然不会串章节。
 */
interface ImageStore {

    fun exists(key: String): Boolean

    /** 存在、非空、且能解码成图片 */
    fun isUsable(key: String): Boolean

    fun delete(key: String)

    /** 原子写入，返回是否成功；失败时不能留下半截文件 */
    fun writeAtomic(key: String, bytes: ByteArray): Boolean
}

/**
 * 带去重与原子写的单图缓存器。
 *
 * [locks] 由调用方共享（同一张图被阅读页、预下载、整本下载同时请求时只下载一次）。
 */
class PersistentImageCache(
    private val store: ImageStore,
    private val isValidData: (ByteArray) -> Boolean,
    private val fetch: suspend (key: String) -> ByteArray?,
    private val locks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap(),
    private val onInvalidData: (String) -> Unit = {},
    private val onFetchFailed: (String, Throwable) -> Unit = { _, _ -> },
    private val onOptimizeFailed: (String, Throwable) -> Unit = { _, _ -> },
    /**
     * 落盘前的可选优化（压缩/转码）。
     *
     * 实现必须自己保证「结果不更大、尺寸不变」，失败时返回原始字节；
     * 这里只负责兜住异常，避免优化环节把一次成功的下载变成失败。
     */
    private val optimize: suspend (ByteArray) -> ByteArray = { it },
) {

    suspend fun ensure(key: String): ImageCacheResult {
        if (store.isUsable(key)) return ImageCacheResult.HIT
        val mutex = lockFor(key)
        val result = try {
            mutex.withLock { ensureLocked(key) }
        } finally {
            pruneLocks()
        }
        return result
    }

    /**
     * 取该键的去重锁。
     *
     * 不用 `computeIfAbsent`：minSdk 21，该方法要 API 24；
     * `putIfAbsent` 从 API 1 就有。
     */
    private fun lockFor(key: String): Mutex {
        locks[key]?.let { return it }
        val created = Mutex()
        return locks.putIfAbsent(key, created) ?: created
    }

    private suspend fun ensureLocked(key: String): ImageCacheResult {
        //等锁期间可能已被别的协程写好
        if (store.isUsable(key)) return ImageCacheResult.HIT
        val bytes = try {
            fetch(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFetchFailed(key, e)
            return ImageCacheResult.FAILED
        } ?: return ImageCacheResult.FAILED
        if (bytes.isEmpty() || !isValidData(bytes)) {
            //HTTP 错误页、验证页、空响应一律不写盘
            onInvalidData(key)
            return ImageCacheResult.FAILED
        }
        //压缩只影响落盘体积，失败/变大都回退到原始字节，不改变「已成功下载」的事实
        val payload = optimizeOrOriginal(key, bytes)
        if (!store.writeAtomic(key, payload)) return ImageCacheResult.FAILED
        //写后回读：落盘的文件必须本身可用，才算缓存成功
        if (!store.isUsable(key)) return ImageCacheResult.FAILED
        return ImageCacheResult.STORED
    }

    /**
     * 执行落盘前优化。
     *
     * - 优化过程被取消 → 必须向上传播（否则整本下载停不下来）；
     * - 优化抛错/返回空 → 记录后回退原始字节，下载结果不受影响；
     * - 优化把内容改大 → 由实现（[MangaImageContent.selectSmallerOrOriginal]）回退，这里不重复判断。
     */
    private suspend fun optimizeOrOriginal(key: String, bytes: ByteArray): ByteArray {
        return try {
            val optimized = optimize(bytes)
            if (optimized.isEmpty()) bytes else optimized
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onOptimizeFailed(key, e)
            bytes
        }
    }

    /** 锁表只增不减会随图库规模膨胀，超过阈值清理一批无人持有的条目 */
    private fun pruneLocks() {
        if (locks.size <= MAX_LOCKS) return
        val iterator = locks.entries.iterator()
        var removed = 0
        while (iterator.hasNext() && removed < MAX_LOCKS / 2) {
            if (!iterator.next().value.isLocked) {
                iterator.remove()
                removed++
            }
        }
    }

    private companion object {
        const val MAX_LOCKS = 4096
    }
}

/** 一批图片（一章）的处理结果 */
data class ImageBatchStats(
    val total: Int,
    val hit: Int = 0,
    val stored: Int = 0,
    val failed: Int = 0,
) {

    val done: Int get() = hit + stored + failed

    val allProcessed: Boolean get() = total == 0 || done >= total
}

/**
 * 有界并发地把一组图片全部过一遍统一缓存入口。
 *
 * - 逐张处理，不预读下一章，内存里只有当前章节的地址列表；
 * - [concurrency] 同时约束在途请求数；
 * - 单张图片抛出的非取消除异常会被计为失败，不影响同批其它图片；
 * - 取消会立刻向上传播（调用方据此停止派发并放弃完整缓存标记）。
 */
suspend fun ensureImageBatch(
    urls: List<String>,
    concurrency: Int,
    onProgress: (done: Int) -> Unit = {},
    ensure: suspend (String) -> ImageCacheResult,
): ImageBatchStats {
    if (urls.isEmpty()) return ImageBatchStats(0)
    val counters = AtomicIntegerArray(3) //按 ImageCacheResult 的 ordinal 下标计数
    val done = AtomicInteger(0)
    coroutineScope {
        val permits = Semaphore(concurrency.coerceAtLeast(1))
        urls.forEach { url ->
            launch {
                permits.withPermit {
                    coroutineContext.ensureActive()
                    val result = try {
                        ensure(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImageCacheResult.FAILED
                    }
                    counters.incrementAndGet(result.ordinal)
                    onProgress(done.incrementAndGet())
                }
            }
        }
    }
    return ImageBatchStats(
        total = urls.size,
        hit = counters.get(ImageCacheResult.HIT.ordinal),
        stored = counters.get(ImageCacheResult.STORED.ordinal),
        failed = counters.get(ImageCacheResult.FAILED.ordinal),
    )
}

/**
 * 缓存文件的容量信息（只用于纯函数裁剪计划）。
 */
data class ImageFileInfo(val name: String, val size: Long, val lastModified: Long)

/**
 * 容量裁剪计划：超出上限时按「最久未修改」优先删除，保留到上限的 80%。
 *
 * 抽成纯函数便于测试；真正的删除由调用方（[io.legado.app.help.book.BookHelp]）执行。
 * 只有「未整本下载」的书会被裁剪，整本缓存由调用方跳过。
 */
fun planImageCacheTrim(files: List<ImageFileInfo>, limitBytes: Long): List<String> {
    if (limitBytes <= 0 || files.isEmpty()) return emptyList()
    var current = files.sumOf { it.size }
    if (current <= limitBytes) return emptyList()
    val target = limitBytes / 10 * 8
    val victims = arrayListOf<String>()
    files.sortedBy { it.lastModified }.forEach { file ->
        if (current > target) {
            victims.add(file.name)
            current -= file.size
        }
    }
    return victims
}

/** 统一的非图片后缀：内容仍是 JPEG/PNG/WebP，仅扩展名不参与图片格式判断 */
const val MANGA_CACHE_SUFFIX = "mcache"

/**
 * 按**内容**判断缓存文件形态的纯函数集合。
 *
 * 这些函数只做字节级判断，不依赖 Android 解码器，因此可以直接在 JVM 上测试；
 * 下载侧（决定写什么名字）、读取侧（找哪个文件）、清理侧（保留哪些名字）共用同一套规则，
 * 避免出现「写 `.mcache`、读 `.jpg`」这类对不上的情况。
 */
object MangaImageContent {

    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val RIFF_HEADER = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_HEADER = "WEBP".toByteArray(Charsets.US_ASCII)
    private val GIF87 = "GIF87a".toByteArray(Charsets.US_ASCII)
    private val GIF89 = "GIF89a".toByteArray(Charsets.US_ASCII)

    fun isPng(bytes: ByteArray): Boolean = startsWith(bytes, PNG_HEADER)

    fun isJpeg(bytes: ByteArray): Boolean = startsWith(bytes, JPEG_HEADER)

    fun isWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (!startsWith(bytes, RIFF_HEADER)) return false
        return regionMatches(bytes, 8, WEBP_HEADER)
    }

    fun isGif(bytes: ByteArray): Boolean =
        startsWith(bytes, GIF87) || startsWith(bytes, GIF89)

    fun isBmp(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()

    /** SVG 是文本，靠前 1KB 里出现 `<svg` 判断（可能有 XML 声明/注释前缀） */
    fun isSvg(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 1024)
        if (limit <= 0) return false
        val head = String(bytes, 0, limit, Charsets.ISO_8859_1).lowercase()
        return head.contains("<svg")
    }

    /** 已知位图格式（可被 BitmapFactory 按内容解码） */
    fun isKnownBitmap(bytes: ByteArray): Boolean =
        isPng(bytes) || isJpeg(bytes) || isWebp(bytes) || isGif(bytes) || isBmp(bytes)

    /**
     * 缓存文件应使用的后缀。
     *
     * - SVG 没有位图编码器，保持 `.svg`，否则真机上会解不出来；
     * - 已知位图统一用 [MANGA_CACHE_SUFFIX]，避免相册/媒体库按扩展名把它当图片；
     * - 无法识别的内容回退到 URL 后缀（保持旧行为，不猜格式）。
     */
    fun suffixFor(bytes: ByteArray, urlSuffix: String): String {
        if (isSvg(bytes)) return "svg"
        if (isKnownBitmap(bytes)) return MANGA_CACHE_SUFFIX
        return urlSuffix.lowercase().ifBlank { "jpg" }
    }

    /**
     * 是否带有可能影响**方向**或**色彩**的元数据段。
     *
     * JPEG 的 APP1/APP2 段承载 EXIF（旋转标记）与 ICC 色彩配置，PNG 的 iCCP/eXIf 同理。
     * BitmapFactory 解码不会自动应用 EXIF 旋转，重新编码会丢掉 ICC，
     * 所以这类图片一律保留原始内容，不做转码 —— 宁可多占空间，也不让漫画页躺倒或偏色。
     */
    fun hasMetadataSegments(bytes: ByteArray): Boolean {
        if (isPng(bytes)) {
            return indexOfAscii(bytes, "iCCP") >= 0 || indexOfAscii(bytes, "eXIf") >= 0
        }
        if (!isJpeg(bytes)) return false
        var index = 2
        while (index + 3 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            val marker = bytes[index + 1].toInt() and 0xFF
            //0xFF 是填充字节，只前进一个，避免把下一个真正的标记当成数据跳过
            if (marker == 0xFF) {
                index++
                continue
            }
            //无长度的标记（RSTn/SOI/EOI/TEM）
            if (marker == 0x01 || marker in 0xD0..0xD9) {
                index += 2
                continue
            }
            //SOS：之后是压缩数据，元数据段已经结束
            if (marker == 0xDA) return false
            val length = ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                    (bytes[index + 3].toInt() and 0xFF)
            if (length < 2) return false
            //APP1 = EXIF/XMP，APP2 = ICC
            if (marker == 0xE1 || marker == 0xE2) return true
            index += 2 + length
        }
        return false
    }

    /**
     * 只有候选结果**更小**且非空时才采用，否则保留原始字节。
     *
     * 这是「压缩不放大文件」的唯一判据，避免不同入口各写一套比较逻辑。
     */
    fun selectSmallerOrOriginal(original: ByteArray, candidate: ByteArray?): ByteArray {
        if (candidate == null || candidate.isEmpty()) return original
        if (candidate.size >= original.size) return original
        return candidate
    }

    /**
     * 按候选顺序查找缓存文件名；调用方负责在 [exists] 中验证可读性与内容。
     *
     * 顺序：新后缀 → `.svg` → 旧版 URL 后缀 → 常见旧后缀。
     * 新旧文件共享同一个 `md5` 前缀，所以「换后缀」不会变成两份缓存；
     * 只有旧缓存时才返回旧名，保证升级后不清缓存也能直接命中。
     */
    fun resolveExistingCacheName(
        baseName: String,
        urlSuffix: String,
        exists: (String) -> Boolean,
    ): String? {
        candidatesFor(baseName, urlSuffix).forEach { name ->
            if (runCatching { exists(name) }.getOrDefault(false)) return name
        }
        return null
    }

    /** 该图所有可能存在的缓存文件名（新后缀优先），供读取与清理共用 */
    fun candidatesFor(baseName: String, urlSuffix: String): List<String> {
        val names = LinkedHashSet<String>(8)
        names.add("$baseName.$MANGA_CACHE_SUFFIX")
        names.add("$baseName.svg")
        if (urlSuffix.isNotBlank()) {
            //旧命名直接用 URL 原始后缀（大小写原样），新命名统一小写，两种都要能命中
            names.add("$baseName.$urlSuffix")
            names.add("$baseName.${urlSuffix.lowercase()}")
        }
        listOf("jpg", "jpeg", "png", "webp", "gif", "bmp").forEach { names.add("$baseName.$it") }
        return names.toList()
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        return regionMatches(bytes, 0, prefix)
    }

    private fun regionMatches(bytes: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset < 0 || bytes.size < offset + target.size) return false
        for (i in target.indices) {
            if (bytes[offset + i] != target[i]) return false
        }
        return true
    }

    private fun indexOfAscii(bytes: ByteArray, ascii: String): Int {
        val needle = ascii.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || bytes.size < needle.size) return -1
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}

/**
 * 缓存身份的**纯函数**部分，下载/读取/清理必须共用，避免三处各算一套。
 *
 * 设计取舍（见交付报告）：键仍然是「图片绝对地址的 MD5」，
 * 而不是 `chapterIndex/imageIndex`：
 * - 绝对地址包含域名与路径，换源后新旧图片地址不同 → 键必然不同，不会串章节；
 * - 章节序号在目录重排（去重、补更、换源）后会漂移，用序号做键反而会把
 *   旧图错配到新章节；
 * - 现有磁盘上的缓存全部是按该规则命名，改成序号会让所有已有缓存作废。
 *
 * 二期改动只换**后缀**，`md5` 前缀不变：新旧缓存共享同一身份，不会被当成两份。
 */
object MangaImageKeys {

    /** 图片绝对地址的 MD5（16 位），新旧命名共用 */
    fun baseName(src: String): String = MD5Utils.md5Encode16(src)

    /**
     * 新写入的缓存文件名：`<md5>.<后缀>`，默认 `mcache`。
     *
     * 内容仍是标准 JPEG/PNG/WebP 数据，只是扩展名不再声明图片类型。
     */
    fun imageFileName(src: String, suffix: String = MANGA_CACHE_SUFFIX): String {
        return "${baseName(src)}.$suffix"
    }

    /** 旧版本命名（URL 后缀），只用于兼容读取与迁移后清理 */
    fun legacyImageFileName(src: String): String {
        return "${baseName(src)}.${UrlUtil.getSuffix(src, "jpg")}"
    }

    /** 书目录名：书名前 9 字 + `md5(bookUrl)`，与既有缓存目录保持一致 */
    fun bookFolderName(book: Book): String = book.getFolderNameNoCache()
}

/**
 * 漫画正文的图片地址提取（纯函数，阅读/下载/清理共用）。
 *
 * 与旧 `AppPattern.imgPattern` 的唯一差异：**懒加载属性优先**。
 *
 * 很多站的标签形如
 * `<img class="lazy" data-src="/p/2.jpg" src="/static/loading.gif">`
 * 旧正则的 `[^>]*src=` 会命中最右边的 `src=`（占位图），
 * 整章地址退化成同一个 `loading.gif`，再被 `distinctUntilChanged()` 折成 1 张 ——
 * 表现就是「一章几百张，只下载/只显示第一张」。
 *
 * 没有懒加载属性时（普通 `<img src>`），输出与旧实现完全一致，
 * 不会让既有缓存键发生漂移。
 */
object MangaImageExtractor {

    private val imgTagPattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE)

    private val lazyAttrPattern = Pattern.compile(
        "\\b(?:data-src|data-original|data-url|data-lazy-src|data-echo)\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )

    /** `(?<![-\\w])` 排除 `data-src` 这种以 src 结尾的属性名 */
    private val plainAttrPattern = Pattern.compile(
        "(?<![-\\w])src\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )

    fun extract(chapterUrl: String, content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val urls = arrayListOf<String>()
        val tagMatcher = imgTagPattern.matcher(content)
        while (tagMatcher.find()) {
            val tag = tagMatcher.group() ?: continue
            val raw = lazyAttrPattern.matcher(tag).let {
                if (it.find()) it.group(1) else null
            } ?: plainAttrPattern.matcher(tag).let {
                if (it.find()) it.group(1) else null
            } ?: continue
            if (raw.isBlank()) continue
            val absolute = NetworkUtils.getAbsoluteURL(chapterUrl, raw)
            if (absolute.isBlank()) continue
            urls.add(absolute)
        }
        return urls
    }

    /**
     * 只折叠**相邻**重复。
     *
     * 与阅读页分页语义一致：真正的连续两页同图会被保留间隔，
     * 而懒加载占位符那种「同一地址刷一整章」会被折成一张。
     */
    fun collapseConsecutive(urls: List<String>): List<String> {
        if (urls.size < 2) return urls
        val result = ArrayList<String>(urls.size)
        var last: String? = null
        urls.forEach {
            if (it != last) {
                result.add(it)
                last = it
            }
        }
        return result
    }
}
