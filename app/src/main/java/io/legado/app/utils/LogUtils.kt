@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

@SuppressLint("SimpleDateFormat")
@Suppress("unused")
object LogUtils {
    const val TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"
    val logTimeFormat by lazy { SimpleDateFormat(TIME_PATTERN) }

    fun init(context: Context) {
        fileHandler = createFileHandler(context)?.also {
            logger.addHandler(it)
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        logger.log(Level.INFO, "$tag $msg")
    }

    inline fun d(tag: String, lazyMsg: () -> String) {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "$tag ${lazyMsg()}")
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        logger.log(Level.WARNING, "$tag $msg")
    }

    val logger: Logger by lazy {
        Logger.getLogger("Legado")
    }

    private var fileHandler: FileHandler? = null

    private fun createFileHandler(context: Context): FileHandler? {
        try {
            val root = context.externalCacheDir ?: return null
            val logFolder = FileUtils.createFolderIfNotExist(root, "logs")
            globalExecutor.execute {
                val expiredTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
                logFolder.listFiles()?.forEach {
                    if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) {
                        it.delete()
                    }
                }
            }
            val date = getCurrentDateStr(TIME_PATTERN).replace(" ", "_").replace(":", "-")
            //诊断日志容量控制：同日追加写（重启不丢），单文件 2MB、滚动 3 段（单日上限 6MB），
            //历史过期文件仍由上方 7 天清理兜底删除。
            val logPath = FileUtils.getPath(root = logFolder, "appLog-${date}_%g.txt")
            return AsyncFileHandler(logPath, limit = 2_000_000, count = 3, append = true).apply {
                formatter = object : java.util.logging.Formatter() {
                    override fun format(record: LogRecord): String {
                        // 设置文件输出格式
                        return getCurrentDateStr(TIME_PATTERN) + ": " + record.message + "\n"
                    }
                }
                level = if (AppConfig.recordLog) {
                    Level.INFO
                } else {
                    Level.OFF
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.putNotSave("创建fileHandler出错\n$e", e)
            return null
        }
    }

    fun upLevel() {
        val level = if (AppConfig.recordLog) {
            Level.INFO
        } else {
            Level.OFF
        }
        fileHandler?.level = level
    }

    /**
     * 获取当前时间
     */
    @SuppressLint("SimpleDateFormat")
    fun getCurrentDateStr(pattern: String): String {
        val date = Date()
        val sdf = SimpleDateFormat(pattern)
        return sdf.format(date)
    }

    fun logDeviceInfo() {
        d("DeviceInfo") {
            buildString {
                kotlin.runCatching {
                    //获取系统信息
                    append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                    append("BRAND=").append(Build.BRAND).append("\n")
                    append("MODEL=").append(Build.MODEL).append("\n")
                    append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                    append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                    val userAgent = try {
                        WebSettings.getDefaultUserAgent(appCtx)
                    } catch (e: Throwable) {
                        e.toString()
                    }
                    append("WebViewUserAgent=").append(userAgent).append("\n")
                    append("packageName=").append(appCtx.packageName).append("\n")
                    append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                    //获取app版本信息
                    AppConst.appInfo.let {
                        append("versionName=").append(it.versionName).append("\n")
                        append("versionCode=").append(it.versionCode).append("\n")
                    }
                }
            }
        }
    }

}

fun Throwable.printOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}

/**
 * 判定协程异常是否属于「未被业务处理」需要记录：
 * - 正常取消（CancellationException，含超时取消与主动取消 ActivelyCancelException）不是错误，不记录；
 * - 已挂 onError 回调的异常由回调各自处理（多数会自行 AppLog），这里不重复记录。
 */
fun Throwable.shouldLogAsUnhandled(hasErrorCallback: Boolean): Boolean {
    if (this is CancellationException) {
        return false
    }
    return !hasErrorCallback
}

/**
 * 协程异常的统一兜底记录：Debug 构建保持原有行为（printOnDebug 标准堆栈输出），不重复记录；
 * Release 构建下为未挂 onError 且未被 onErrorReturn 消化的异常计入 AppLog，
 * 避免异常完全静默（修复前 printOnDebug 在 release 是空操作）。
 * 只记录异常类名、消息与堆栈（AppLog 既有惯例），不记录请求头、密码或响应内容。
 */
fun Throwable.logIfUnhandled(hasErrorCallback: Boolean) {
    if (BuildConfig.DEBUG) {
        return
    }
    if (!shouldLogAsUnhandled(hasErrorCallback)) {
        return
    }
    runCatching {
        AppLog.put("协程异常未处理\n$this", this)
    }
}
