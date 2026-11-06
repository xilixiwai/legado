package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 缓存书籍服务
 */
class CacheBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private val threadCount = AppConfig.threadCount
    private var cachePool =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var downloadJob: Job? = null
    private var accepting = true
    private var latestStartId = 0
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private var mutex = Mutex()
    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CacheBookService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                notificationContent = CacheBook.downloadSummary
                upCacheBookNotification()
                postEvent(EventBus.UP_DOWNLOAD, "")
                if (accepting && downloadJob == null) download()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> {
                    accepting = true
                    addDownloadData(
                        intent.getStringExtra("bookUrl"),
                        intent.getIntExtra("start", 0),
                        intent.getIntExtra("end", 0)
                    )
                    download()
                }

                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> {
                    stopDownloads()
                    stopSelfResult(startId)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        stopDownloads()
        cachePool.close()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    private fun stopDownloads() {
        accepting = false
        // Invalidate preparation and chapter identities synchronously, before cancel callbacks.
        CacheBook.close()
        downloadJob?.cancel()
        downloadJob = null
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        bookUrl ?: return
        CacheBook.preparations.launch(bookUrl, lifecycleScope, IO) { ticket ->
            try {
                val cacheBook = CacheBook.preparations.withCurrent(ticket) {
                    CacheBook.getOrCreate(bookUrl)
                } ?: return@launch
                val job = CacheBook.preparations.withCurrent(ticket) {
                    cacheBook.prepare(lifecycleScope, IO, start, end, load = { token ->
                        // Parsing mutates Book, so an obsolete preparation must not share the live copy.
                        val book = cacheBook.withPreparation(token) {
                            cacheBook.book.let { original ->
                                original.copy().apply {
                                    infoHtml = original.infoHtml
                                    tocHtml = original.tocHtml
                                }
                            }
                        }
                        val source = cacheBook.withPreparation(token) { cacheBook.bookSource }
                        val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
                        if (chapterCount == 0) {
                            mutex.withLock {
                                cacheBook.withPreparation(token) { Unit }
                                if (book.tocUrl.isEmpty()) {
                                    WebBook.getBookInfoAwait(source, book)
                                    currentCoroutineContext().ensureActive()
                                    cacheBook.withPreparation(token) { Unit }
                                }
                                val result = WebBook.getChapterListAwait(source, book)
                                currentCoroutineContext().ensureActive()
                                result.exceptionOrNull()?.let { error ->
                                    if (error is CancellationException) throw error
                                    cacheBook.withPreparation(token) {
                                        if (book.totalChapterNum > 0) {
                                            book.totalChapterNum = 0
                                            book.update()
                                        }
                                    }
                                    throw error
                                }
                                val toc = result.getOrThrow()
                                cacheBook.withPreparation(token) {
                                    appDb.bookChapterDao.insert(*toc.toTypedArray())
                                    book.update()
                                    cacheBook.book = book
                                }
                            }
                        }
                        book.lastChapterIndex
                    }, failed = { error ->
                        AppLog.put("《${cacheBook.book.name}》缓存准备失败\n${error.localizedMessage}", error, true)
                    })
                }
                job.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CacheBook.preparations.withCurrent(ticket) {
                    AppLog.put("缓存准备失败\n${e.localizedMessage}", e, true)
                }
            }
        }
    }

    private fun removeDownload(bookUrl: String?) {
        bookUrl ?: return
        CacheBook.preparations.stop(bookUrl)
        CacheBook.cacheBookMap[bookUrl]?.stop()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (accepting) download()
    }

    private fun download() {
        if (!accepting || downloadJob != null) return
        // Main dispatcher serializes start commands and the worker's final stop decision.
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(cachePool) { CacheBook.startProcessJob(cachePool) }
            } finally {
                if (downloadJob === currentCoroutineContext()[Job]) {
                    downloadJob = null
                    if (accepting) {
                        if (CacheBook.isRun) download() else stopSelfResult(latestStartId)
                    }
                }
            }
        }
        downloadJob = job
        job.start()
    }

    private fun upCacheBookNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        startForeground(NotificationId.CacheBookService, notification)
    }

}