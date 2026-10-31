package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.MangaCacheService
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 整本漫画下载任务。
 *
 * 与小说下载（[CacheBook]）完全独立：不复用它的任务模型与统计口径，
 * 只复用「前台服务 + 通知 + 图片落盘/去重/解码」这些基础设施。
 *
 * 资源约束：
 * - **逐章推进**，一次只在内存里保留当前章节的图片地址列表；
 * - 图片并发固定 [CONCURRENCY] 路，配合 [BookHelp.saveImageChecked] 内部
 *   `AnalyzeUrl` 的书源并发率限制，不会绕过书源限流；
 * - 已落盘图片直接跳过，失败图片按 [MAX_RETRY] 重试后计入失败数。
 */
object MangaCache {

    /** 图片并发路数。取 4：低于 `AppConfig.threadCount`(默认 16)，避免整本下载打满连接 */
    const val CONCURRENCY = 4

    /** 单张图片失败后的重试次数（不含首次） */
    const val MAX_RETRY = 2

    private val tasks = ConcurrentHashMap<String, MangaCacheTask>()

    /**
     * 任务执行作用域固定在 IO。
     *
     * 不能沿用调用方（前台服务）的 `lifecycleScope`：那是 Main 调度器，
     * 网络请求会直接触发 NetworkOnMainThreadException。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次任务结束时的一句话结果，供通知展示最终状态 */
    @Volatile
    var lastFinishText: String? = null
        private set

    val isRun: Boolean
        get() = tasks.isNotEmpty()

    fun isRunning(bookUrl: String): Boolean = tasks.containsKey(bookUrl)

    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (book.isLocal) return
        context.startService<MangaCacheService> {
            action = IntentAction.start
            putExtra("bookUrl", book.bookUrl)
            putExtra("start", start)
            putExtra("end", end)
        }
    }

    /**
     * 创建任务。同一时间只允许一个整本下载任务：
     * 多任务会让单条通知无法表达各自进度，图片并发叠加也更容易触发书源限流。
     *
     * @return true 表示任务已创建
     */
    internal fun addTask(bookUrl: String, start: Int, end: Int): Boolean {
        //避免上一次任务的结果被当成本次结果展示
        lastFinishText = null
        if (tasks.isNotEmpty()) return false
        val book = appDb.bookDao.getBook(bookUrl) ?: return false
        if (book.isLocal) return false
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return false
        val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
        if (chapterCount <= 0) return false
        val task = MangaCacheTask(book, bookSource, start, end, chapterCount)
        if (tasks.putIfAbsent(bookUrl, task) != null) return false
        task.launch(workScope)
        return true
    }

    fun stopAll() {
        tasks.values.forEach { it.cancel() }
    }

    fun progress(): MangaCacheProgress? = tasks.values.firstOrNull()?.snapshot()

    internal fun onTaskFinish(task: MangaCacheTask) {
        //先发布最终统计，服务观察到任务移除时即可读取完整结果。
        lastFinishText = task.finishText()
        if (tasks[task.book.bookUrl] === task) {
            tasks.remove(task.book.bookUrl)
        }
        postEvent(EventBus.UP_DOWNLOAD, task.book.bookUrl)
    }

}

internal class MangaCacheTask(
    val book: Book,
    private val bookSource: BookSource,
    start: Int,
    end: Int,
    chapterCount: Int,
) {

    enum class State { RUNNING, SUCCESS, PARTIAL_FAILED, ALL_FAILED, CANCELED }

    val startIndex = start.coerceIn(0, chapterCount - 1)
    val endIndex = end.coerceIn(startIndex, chapterCount - 1)
    val totalChapters = endIndex - startIndex + 1

    /** 只有覆盖了整本（第 0 章到最后一章）才算「整本已缓存」 */
    private val isWholeBook = startIndex == 0 && endIndex == chapterCount - 1

    /** 四路图片结果与通知快照使用同一把短锁，避免读取到相互矛盾的计数。 */
    private val progressLock = Any()
    private var doneChapters = 0
    private var discoveredChapters = 0
    private var discoveredImages = 0

    /** 已获得并发槽、正在处理的图片数（onStart 与最终结果一一配对） */
    private var downloadingImages = 0
    private var curImageDone = 0
    private var curImageTotal = 0
    private var storedImages = 0
    private var cachedImages = 0
    private var failedImages = 0
    private var failedChapters = 0

    /** 正文拿到了但解析不出任何图片的章节数（多半是正文规则失效/懒加载占位） */
    private var emptyChapters = 0

    @Volatile
    var state = State.RUNNING
        private set

    private var job: Job? = null

    fun launch(scope: CoroutineScope) {
        job = scope.launch { run() }
    }

    fun cancel() {
        synchronized(progressLock) {
            if (state == State.RUNNING) {
                state = State.CANCELED
            }
        }
        job?.cancel()
    }

    fun snapshot(): MangaCacheProgress = synchronized(progressLock) {
        MangaCacheProgress(
            bookName = book.name,
            doneChapters = doneChapters,
            totalChapters = totalChapters,
            curChapterDone = curImageDone,
            curChapterTotal = curImageTotal,
            discoveredImages = discoveredImages,
            downloadingImages = downloadingImages,
            cachedImages = cachedImages,
            storedImages = storedImages,
            totalImagesKnown = discoveredChapters == totalChapters,
            failedImages = failedImages,
            failedChapters = failedChapters,
            running = state == State.RUNNING,
        )
    }

    fun finishText(): String = synchronized(progressLock) {
        val stateText = when (state) {
            State.SUCCESS -> "下载完成"
            State.PARTIAL_FAILED -> "部分失败"
            State.ALL_FAILED -> "全部失败"
            State.CANCELED -> "已取消"
            State.RUNNING -> "已结束"
        }
        buildString {
            append(stateText).append(" · ").append(snapshot().text)
            if (emptyChapters > 0) {
                append(" · 无图章节 ").append(emptyChapters)
            }
        }
    }

    private suspend fun run() {
        try {
            processChapters()
            //完整标记只能由「整本范围 + 全部图片已验证」产生
            if (state == State.SUCCESS && isWholeBook) {
                BookHelp.markMangaFullCached(book)
            }
        } catch (e: CancellationException) {
            state = State.CANCELED
            throw e
        } catch (e: Throwable) {
            if (state == State.RUNNING) {
                state = if (snapshot().successImages == 0) State.ALL_FAILED else State.PARTIAL_FAILED
            }
            AppLog.put("《${book.name}》整本漫画下载中断\n${e.localizedMessage}", e)
        } finally {
            if (state == State.RUNNING) {
                state = State.CANCELED
            }
            MangaCache.onTaskFinish(this)
        }
    }

    /** 逐章执行与统计；IO 和通知回调可替换，以行为测试覆盖真实任务状态流转。 */
    internal suspend fun processChapters(
        download: suspend (
            index: Int,
            onTotal: (Int) -> Unit,
            onStart: () -> Unit,
            onResult: (ImageCacheResult) -> Unit,
        ) -> Unit = ::downloadChapter,
        onChapterDone: () -> Unit = { postEvent(EventBus.UP_DOWNLOAD, book.bookUrl) },
        onChapterError: (Int, Exception) -> Unit = { index, error ->
            AppLog.put("《${book.name}》第${index + 1}章处理失败\n${error.localizedMessage}", error)
        },
    ) {
        try {
            for (index in startIndex..endIndex) {
                currentCoroutineContext().ensureActive()
                if (state != State.RUNNING) break
                synchronized(progressLock) {
                    curImageDone = 0
                    curImageTotal = 0
                }
                var discovered = false
                var chapterFailed = false
                try {
                    download(index, { total ->
                        synchronized(progressLock) {
                            discovered = true
                            discoveredChapters++
                            discoveredImages += total
                            curImageTotal = total
                        }
                    }, {
                        //真正获得并发槽、开始处理本张；与最终结果回调一一配对
                        synchronized(progressLock) { downloadingImages++ }
                    }, { result ->
                        synchronized(progressLock) {
                            when (result) {
                                ImageCacheResult.HIT -> cachedImages++
                                ImageCacheResult.STORED -> storedImages++
                                ImageCacheResult.FAILED -> failedImages++
                            }
                            if (downloadingImages > 0) downloadingImages--
                            curImageDone++
                        }
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    //已经发现的图片及已完成结果保留；章节处理异常单独统计。
                    synchronized(progressLock) { failedChapters++ }
                    chapterFailed = true
                    onChapterError(index, e)
                }
                synchronized(progressLock) {
                    if (!discovered && !chapterFailed) failedChapters++
                    doneChapters++
                }
                onChapterDone()
            }
            synchronized(progressLock) {
                if (state == State.RUNNING) {
                    state = when {
                        cachedImages + storedImages == 0 -> State.ALL_FAILED
                        failedImages == 0 && failedChapters == 0 && emptyChapters == 0 -> State.SUCCESS
                        else -> State.PARTIAL_FAILED
                    }
                }
            }
        } catch (e: CancellationException) {
            state = State.CANCELED
            throw e
        }
    }

    /**
     * 处理一章：取正文 -> 解析图片地址 -> 逐张走统一缓存入口。
     *
     * 处理单位是**图片**而不是章节：一章可能有几十到几百张图，
     * 只看 `doneChapters` 会得出「下完了」的假结论。
     * 只保留当前章节的地址列表，章节间不累积。
     */
    private suspend fun downloadChapter(
        index: Int,
        onTotal: (Int) -> Unit,
        onStart: () -> Unit,
        onResult: (ImageCacheResult) -> Unit,
    ) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        //卷节点本身是目录分组，不含图片：跳过，且既不算失败也不算「无图章节」
        if (chapter.isVolume) {
            onTotal(0)
            return
        }
        val content = loadContent(chapter) ?: return
        val stats = BookHelp.ensureChapterImages(
            bookSource = bookSource,
            book = book,
            chapter = chapter,
            content = content,
            concurrency = MangaCache.CONCURRENCY,
            retryEach = MangaCache.MAX_RETRY,
            onTotal = onTotal,
            onResult = onResult,
            onStart = onStart,
        )
        if (stats.total == 0) {
            //正文在但解析不出图片：不能当作「下完了一章」
            synchronized(progressLock) { emptyChapters++ }
            return
        }
    }

    private suspend fun loadContent(chapter: BookChapter): String? {
        BookHelp.getContent(book, chapter)?.let { return it }
        return try {
            //needSave 默认 true，顺带把正文落到缓存，下次不必再请求
            WebBook.getContentAwait(bookSource, book, chapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("《${book.name}》${chapter.title} 正文获取失败\n${e.localizedMessage}", e)
            null
        }
    }

}

/**
 * 单张图片的重试策略：首次失败后最多重试 [maxRetry] 次，间隔递增（1s、2s…）。
 *
 * 抽成独立函数便于 JVM 单测，不需要网络、数据库或 Android 环境。
 * [attempt] 返回 false 表示本次失败；[onRetryDelay] 只在实际重试前调用。
 *
 * @return true 表示最终成功
 */
internal suspend fun retryDownload(
    maxRetry: Int,
    onRetryDelay: suspend (attempt: Int) -> Unit = { delay(1000L * it) },
    attempt: suspend () -> Boolean,
): Boolean {
    var retried = 0
    while (true) {
        if (attempt()) return true
        retried++
        if (retried > maxRetry) return false
        onRetryDelay(retried)
    }
}

