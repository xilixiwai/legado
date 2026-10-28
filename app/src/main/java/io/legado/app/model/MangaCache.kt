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
import io.legado.app.utils.onEachParallel
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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        if (tasks[task.book.bookUrl] === task) {
            tasks.remove(task.book.bookUrl)
        }
        lastFinishText = task.finishText()
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

    private val doneChapters = AtomicInteger(0)
    private val curImageDone = AtomicInteger(0)
    private val curImageTotal = AtomicInteger(0)
    private val successImages = AtomicInteger(0)
    private val skippedImages = AtomicInteger(0)
    private val failedImages = AtomicInteger(0)
    private val failedChapters = AtomicInteger(0)

    @Volatile
    var state = State.RUNNING
        private set

    private var job: Job? = null

    fun launch(scope: CoroutineScope) {
        job = scope.launch { run() }
    }

    fun cancel() {
        if (state == State.RUNNING) {
            state = State.CANCELED
        }
        job?.cancel()
    }

    fun snapshot(): MangaCacheProgress = MangaCacheProgress(
        bookName = book.name,
        doneChapters = doneChapters.get(),
        totalChapters = totalChapters,
        curChapterDone = curImageDone.get(),
        curChapterTotal = curImageTotal.get(),
        failedImages = failedImages.get(),
        failedChapters = failedChapters.get(),
        running = state == State.RUNNING,
    )

    fun finishText(): String {
        val stateText = when (state) {
            State.SUCCESS -> "下载完成"
            State.PARTIAL_FAILED -> "部分失败"
            State.ALL_FAILED -> "全部失败"
            State.CANCELED -> "已取消"
            State.RUNNING -> "已结束"
        }
        return buildString {
            append('《').append(book.name).append("》 ").append(stateText)
            append(" · ").append(doneChapters.get()).append('/').append(totalChapters).append(" 章")
            append(" · 成功 ").append(successImages.get() + skippedImages.get()).append(" 张")
            append(" · 失败 ").append(failedImages.get()).append(" 张")
            if (failedChapters.get() > 0) {
                append(" · 章节失败 ").append(failedChapters.get())
            }
        }
    }

    private suspend fun run() {
        try {
            for (index in startIndex..endIndex) {
                currentCoroutineContext().ensureActive()
                if (state != State.RUNNING) break
                downloadChapter(index)
                doneChapters.incrementAndGet()
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
            if (state == State.RUNNING) {
                state = when {
                    failedImages.get() == 0 && failedChapters.get() == 0 -> State.SUCCESS
                    successImages.get() + skippedImages.get() == 0 -> State.ALL_FAILED
                    else -> State.PARTIAL_FAILED
                }
                if (state == State.SUCCESS && isWholeBook) {
                    BookHelp.markMangaFullCached(book)
                }
            }
        } catch (e: CancellationException) {
            state = State.CANCELED
            throw e
        } catch (e: Throwable) {
            if (state == State.RUNNING) {
                state = State.PARTIAL_FAILED
            }
            AppLog.put("《${book.name}》整本漫画下载中断\n${e.localizedMessage}", e)
        } finally {
            if (state == State.RUNNING) {
                state = State.CANCELED
            }
            MangaCache.onTaskFinish(this)
        }
    }

    /**
     * 处理一章：取正文 -> 解析图片地址 -> 受限并发下载。
     * 只保留当前章节的地址列表，章节间不累积。
     */
    private suspend fun downloadChapter(index: Int) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (chapter.isVolume) return
        val content = loadContent(chapter) ?: return
        val urls = BookHelp.flowImages(chapter, content)
            .distinctUntilChanged()
            .toList()
        curImageTotal.set(urls.size)
        curImageDone.set(0)
        if (urls.isEmpty()) return
        urls.asFlow()
            .onEachParallel(MangaCache.CONCURRENCY) { url ->
                downloadImage(chapter, url)
            }
            .collect()
    }

    private suspend fun loadContent(chapter: BookChapter): String? {
        BookHelp.getContent(book, chapter)?.let { return it }
        return try {
            //needSave 默认 true，顺带把正文落到缓存，下次不必再请求
            WebBook.getContentAwait(bookSource, book, chapter)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedChapters.incrementAndGet()
            AppLog.put("《${book.name}》${chapter.title} 正文获取失败\n${e.localizedMessage}", e)
            null
        }
    }

    private suspend fun downloadImage(chapter: BookChapter, url: String) {
        currentCoroutineContext().ensureActive()
        if (BookHelp.isImageUsable(book, url)) {
            skippedImages.incrementAndGet()
            curImageDone.incrementAndGet()
            return
        }
        val ok = retryDownload(MangaCache.MAX_RETRY) {
            BookHelp.saveImageChecked(bookSource, book, url, chapter)
        }
        if (ok) {
            successImages.incrementAndGet()
        } else {
            failedImages.incrementAndGet()
        }
        curImageDone.incrementAndGet()
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

