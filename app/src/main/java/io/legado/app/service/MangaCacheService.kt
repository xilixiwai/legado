package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.model.MangaCache
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager

/**
 * 整本漫画下载服务。
 *
 * 与小说缓存服务（[CacheBookService]）共享「通知渠道 + 通知样式 + 前台服务基础设施 +
 * 取消动作模式」，但使用**独立通知 ID** 和**独立任务状态**，不读写 [io.legado.app.model.CacheBook]
 * 的任何字段，因此不会覆盖或污染小说下载进度。
 *
 * 通知刷新沿用小说缓存的节流方式：每 1 秒最多更新一次，`setOnlyAlertOnce(true)`
 * 保证不重复响铃，避免每张图片完成都刷一次通知。
 */
class MangaCacheService : BaseService() {

    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<MangaCacheService>(IntentAction.stop)
        )
        builder
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (!MangaCache.isRun) {
                    finishNotification()
                    break
                }
                upNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //先交给 BaseService：它会完成 startForeground，之后再决定是否 stopSelf 才安全
        val result = super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            IntentAction.start -> {
                val bookUrl = intent.getStringExtra("bookUrl")
                val added = bookUrl != null && MangaCache.addTask(
                    bookUrl,
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", -1)
                )
                if (added) {
                    upNotification()
                } else if (!MangaCache.isRun) {
                    //没有可执行的任务（书不存在 / 无书源 / 目录为空 / 书已在队列但进程状态丢失）
                    stopSelf()
                }
            }

            IntentAction.stop -> MangaCache.stopAll()
        }
        return result
    }

    override fun onDestroy() {
        MangaCache.stopAll()
        super.onDestroy()
    }

    override fun startForegroundNotification() {
        val progress = MangaCache.progress()
        if (progress != null) {
            notificationBuilder
                .setContentText(progress.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(progress.text))
                .setProgress(100, progress.percent, progress.indeterminate)
        } else {
            notificationBuilder.setContentText(getString(R.string.service_starting))
        }
        startForeground(NotificationId.MangaCacheService, notificationBuilder.build())
    }

    private fun upNotification() {
        val progress = MangaCache.progress() ?: return
        notificationBuilder
            .setContentText(progress.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress.text))
            .setProgress(100, progress.percent, progress.indeterminate)
        notificationManager.notify(NotificationId.MangaCacheService, notificationBuilder.build())
    }

    /**
     * 任务结束：把最终状态写进通知，并解除前台占用，
     * 让用户能看到「完成 / 部分失败 / 全部失败 / 已取消」并可自行划掉。
     */
    private fun finishNotification() {
        val text = MangaCache.lastFinishText
        if (text != null) {
            notificationBuilder
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .clearActions()
            notificationManager.notify(NotificationId.MangaCacheService, notificationBuilder.build())
            //DETACH：保留通知但不再绑前台服务
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

}
