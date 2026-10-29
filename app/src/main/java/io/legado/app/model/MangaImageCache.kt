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
 * 2. 本地文件不可用时**先删除**再下载，坏文件不会永久阻塞重试；
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
        //坏文件/半截文件必须先清掉，否则会永远挡住重试
        if (store.exists(key)) {
            store.delete(key)
        }
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
        if (!store.writeAtomic(key, bytes)) return ImageCacheResult.FAILED
        //写后回读：落盘的文件必须本身可用，才算缓存成功
        if (!store.isUsable(key)) {
            store.delete(key)
            return ImageCacheResult.FAILED
        }
        return ImageCacheResult.STORED
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

/**
 * 缓存身份的**纯函数**部分，下载/读取/清理必须共用，避免三处各算一套。
 *
 * 设计取舍（见交付报告）：键仍然是「图片绝对地址的 MD5」，
 * 而不是 `chapterIndex/imageIndex`：
 * - 绝对地址包含域名与路径，换源后新旧图片地址不同 → 键必然不同，不会串章节；
 * - 章节序号在目录重排（去重、补更、换源）后会漂移，用序号做键反而会把
 *   旧图错配到新章节；
 * - 现有磁盘上的缓存全部是按该规则命名，改成序号会让所有已有缓存作废。
 */
object MangaImageKeys {

    /** 图片文件名：`<md5(绝对地址)16位>.<后缀>` */
    fun imageFileName(src: String): String {
        return "${MD5Utils.md5Encode16(src)}.${UrlUtil.getSuffix(src, "jpg")}"
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
