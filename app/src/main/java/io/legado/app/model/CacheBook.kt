package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.CacheBookService
import io.legado.app.utils.onEachParallel
import io.legado.app.utils.postEvent
import io.legado.app.utils.startService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

object CacheBook {

    val cacheBookMap = ConcurrentHashMap<String, CacheBookModel>()

    private val workingState = MutableStateFlow(true)
    private val mutex = Mutex()

    @Synchronized
    fun getOrCreate(bookUrl: String): CacheBookModel? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val bookSource = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[bookUrl] = cacheBook
        return cacheBook
    }

    @Synchronized
    fun getOrCreate(bookSource: BookSource, book: Book): CacheBookModel {
        updateBookSource(bookSource)
        var cacheBook = cacheBookMap[book.bookUrl]
        if (cacheBook != null) {
            //存在时更新,书源可能会变化,必须更新
            cacheBook.bookSource = bookSource
            cacheBook.book = book
            return cacheBook
        }
        cacheBook = CacheBookModel(bookSource, book)
        cacheBookMap[book.bookUrl] = cacheBook
        return cacheBook
    }

    private fun updateBookSource(newBookSource: BookSource) {
        cacheBookMap.forEach {
            val model = it.value
            if (model.bookSource.bookSourceUrl == newBookSource.bookSourceUrl) {
                model.bookSource = newBookSource
            }
        }
    }

    fun start(context: Context, book: Book, start: Int, end: Int) {
        if (!book.isLocal) {
            context.startService<CacheBookService> {
                action = IntentAction.start
                putExtra("bookUrl", book.bookUrl)
                putExtra("start", start)
                putExtra("end", end)
            }
        }
    }

    fun remove(context: Context, bookUrl: String) {
        context.startService<CacheBookService> {
            action = IntentAction.remove
            putExtra("bookUrl", bookUrl)
        }
    }

    fun stop(context: Context) {
        if (CacheBookService.isRun) {
            context.startService<CacheBookService> {
                action = IntentAction.stop
            }
        }
    }

    fun close() {
        preparations.stopAll()
        cacheBookMap.forEach { (url, model) ->
            model.stop()
            cacheBookMap.remove(url, model)
        }
        successDownloadSet.clear()
        errorDownloadMap.clear()
    }

    fun setWorkingState(value: Boolean) {
        workingState.value = value
    }

    suspend fun startProcessJob(context: CoroutineContext) = mutex.withLock {
        setWorkingState(true)
        flow {
            while (currentCoroutineContext().isActive && (cacheBookMap.isNotEmpty() || preparations.isRunning())) {
                var emitted = false

                cacheBookMap.forEach { (_, model) ->
                    if (!model.isLoading() && model.waitCount > 0) {
                        emit(model)
                        emitted = true
                    } else {
                        model.removeIfIdle()
                    }
                    workingState.first { it }
                }

                if (!emitted) {
                    delay(1000)
                }
            }
        }.onStart {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.onEachParallel(AppConfig.threadCount) {
            coroutineScope {
                it.download(this, context)
            }
        }.onCompletion {
            postEvent(EventBus.UP_DOWNLOAD_STATE, "")
        }.collect()
    }


    val downloadSummary: String
        get() {
            return "正在下载:${onDownloadCount}|等待中:${waitCount}|失败:${errorDownloadMap.count()}|成功:${successDownloadSet.size}"
        }

    val isRun: Boolean
        get() {
            if (preparations.isRunning()) return true
            cacheBookMap.forEach {
                if (it.value.isRun()) {
                    return true
                }
            }
            return false
        }

    private val waitCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.waitCount
            }
            return count
        }

    val onDownloadCount: Int
        get() {
            var count = 0
            cacheBookMap.forEach {
                count += it.value.onDownloadCount
            }
            return count
        }

    val successDownloadSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    val errorDownloadMap = ConcurrentHashMap<String, Int>()
    private val imageResults = CacheBookImageResults()
    internal val preparations = CacheBookPreparations()

    fun recentImageResult(bookUrl: String, chapterIndex: Int): CacheBookImageResult? =
        imageResults.get(bookUrl, chapterIndex)

    fun recentImageResults(bookUrl: String): List<CacheBookImageResult> =
        imageResults.forBook(bookUrl)

    class CacheBookModel(var bookSource: BookSource, var book: Book) {

        // Foreground ReadBook requests keep their text-only semantics and separate identities.
        private val reading = hashMapOf<Int, Any>()
        private val run = CacheBookRun(
            book.bookUrl, { book.isImage }, imageResults,
            onPresent = { cacheBookMap[book.bookUrl] = this },
            onIdle = {
                if (reading.isEmpty() && !preparations.contains(book.bookUrl)) {
                    cacheBookMap.remove(book.bookUrl, this)
                }
            },
            onChanged = { postEvent(EventBus.UP_DOWNLOAD, book.bookUrl) },
        )

        val waitCount get() = run.waitCount()
        val onDownloadCount get() = run.activeCount() + synchronized(run) { reading.size }

        init {
            postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
        }

        fun isRun(): Boolean = run.isRunning() || synchronized(run) { reading.isNotEmpty() }
        fun isStop(): Boolean = !isRun()
        fun isLoading(): Boolean = run.isPreparing()

        internal fun removeIfIdle() = synchronized(run) {
            if (!isRun() && !preparations.contains(book.bookUrl)) {
                cacheBookMap.remove(book.bookUrl, this)
            }
        }

        fun stop() {
            preparations.stop(book.bookUrl)
            synchronized(run) {
                reading.clear()
                run.stop()
            }
        }

        fun addDownload(start: Int, end: Int) = run.add(start, end)

        internal fun prepare(
            scope: CoroutineScope,
            context: CoroutineContext,
            start: Int,
            end: Int,
            load: suspend (CacheBookRun.Preparation) -> Int,
            failed: (Throwable) -> Unit,
        ) = run.prepare(scope, context, start, end, load, failed)

        internal fun <T> withPreparation(token: CacheBookRun.Preparation, action: () -> T): T =
            run.withPreparation(token, action)

        /** Each actually scheduled manga chapter obtains one complete batch result. */
        fun download(scope: CoroutineScope, context: CoroutineContext) {
            run.schedule(scope, context) { index ->
                if (synchronized(run) { reading.containsKey(index) }) return@schedule null
                val taskBook = book
                val source = bookSource
                val chapter = appDb.bookChapterDao.getChapter(taskBook.bookUrl, index)
                    ?: return@schedule null
                object : CacheBookChapterWork {
                    override val chapterUrl = chapter.url
                    override val isManga = taskBook.isImage
                    override val isVolume = chapter.isVolume
                    override fun hasContent() = BookHelp.hasContent(taskBook, chapter)
                    override fun hasImages() = BookHelp.hasImageContent(taskBook, chapter)
                    override suspend fun readContent() = BookHelp.getContent(taskBook, chapter)
                    override suspend fun fetchContent() = WebBook.getContentAwait(source, taskBook, chapter)
                    override suspend fun ensureImages(content: String) =
                        BookHelp.ensureChapterImages(source, taskBook, chapter, content, 1)
                    override suspend fun saveTextImages(content: String) {
                        BookHelp.saveImages(source, taskBook, chapter, content, 1)
                    }
                    override fun started() {
                        if (isManga) successDownloadSet.remove(chapter.primaryStr())
                    }
                    override fun deliver(content: String) = downloadFinish(chapter, content)
                    override fun success() {
                        successDownloadSet.add(chapter.primaryStr())
                        errorDownloadMap.remove(chapter.primaryStr())
                    }
                    override fun failure(error: Throwable, count: Int, terminal: Boolean) {
                        successDownloadSet.remove(chapter.primaryStr())
                        errorDownloadMap[chapter.primaryStr()] = count
                        if (terminal) AppLog.put("下载${taskBook.name}-${chapter.title}失败\n${error.localizedMessage}", error)
                    }
                    override fun skipVolume() {
                        postEvent(EventBus.SAVE_CONTENT, Pair(taskBook, chapter))
                    }
                }
            }
        }

        private fun readingCommit(chapter: BookChapter, token: Any, action: () -> Unit): Boolean =
            synchronized(run) {
                if (reading[chapter.index] !== token) return@synchronized false
                action()
                true
            }

        private fun readingSuccess(chapter: BookChapter) {
            successDownloadSet.add(chapter.primaryStr())
            errorDownloadMap.remove(chapter.primaryStr())
            ReadBook.downloadedChapters.add(chapter.index)
            ReadBook.downloadFailChapters.remove(chapter.index)
        }

        private fun readingError(chapter: BookChapter, error: Throwable) {
            val key = chapter.primaryStr()
            val count = (errorDownloadMap[key] ?: 0) + if (error is ConcurrentException) 0 else 1
            errorDownloadMap[key] = count
            reading.remove(chapter.index)
            if (count < 3) run.retryFromReading(chapter.index, count)
            else AppLog.put("下载${book.name}-${chapter.title}失败\n${error.localizedMessage}", error)
            ReadBook.downloadFailChapters[chapter.index] = (ReadBook.downloadFailChapters[chapter.index] ?: 0) + 1
        }

        suspend fun downloadAwait(chapter: BookChapter): String {
            val token = Any()
            synchronized(run) {
                reading[chapter.index] = token
                run.removeQueued(chapter.index)
            }
            try {
                val content = WebBook.getContentAwait(bookSource, book, chapter)
                currentCoroutineContext().ensureActive()
                if (!readingCommit(chapter, token) { readingSuccess(chapter) }) {
                    throw CancellationException("CacheBook reading request superseded")
                }
                return content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!readingCommit(chapter, token) { readingError(chapter, e) }) {
                    throw CancellationException("CacheBook reading request superseded")
                }
                return "获取正文失败\n${e.localizedMessage}"
            } finally {
                synchronized(run) { if (reading[chapter.index] === token) reading.remove(chapter.index) }
                postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
            }
        }

        fun download(
            scope: CoroutineScope,
            chapter: BookChapter,
            semaphore: Semaphore?,
            resetPageOffset: Boolean = false
        ) {
            synchronized(run) {
                if (reading.containsKey(chapter.index) || run.hasActive(chapter.index)) return
                val token = Any()
                reading[chapter.index] = token
                run.removeQueued(chapter.index)
                val task = WebBook.getContent(
                    scope, bookSource, book, chapter,
                    start = CoroutineStart.LAZY, executeContext = IO, semaphore = semaphore
                ).onSuccess { content ->
                    readingCommit(chapter, token) {
                        readingSuccess(chapter)
                        downloadFinish(chapter, content, resetPageOffset)
                    }
                }.onError { error ->
                    if (error !is CancellationException) readingCommit(chapter, token) {
                        readingError(chapter, error)
                        downloadFinish(chapter, "获取正文失败\n${error.localizedMessage}", resetPageOffset)
                    }
                }
                task.invokeOnCompletion { cause ->
                    synchronized(run) {
                        if (cause is CancellationException) readingCommit(chapter, token) {
                            downloadFinish(chapter, "download canceled", resetPageOffset, true)
                        }
                        if (reading[chapter.index] === token) reading.remove(chapter.index)
                    }
                    postEvent(EventBus.UP_DOWNLOAD, book.bookUrl)
                }
                task.start()
            }
        }

        private fun downloadFinish(
            chapter: BookChapter,
            content: String,
            resetPageOffset: Boolean = false,
            canceled: Boolean = false
        ) {
            if (ReadBook.book?.bookUrl == book.bookUrl) {
                ReadBook.contentLoadFinish(
                    book, chapter, content,
                    resetPageOffset = resetPageOffset,
                    canceled = canceled
                )
            }
        }

    }

}
