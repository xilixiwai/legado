package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig

/**
 * [诊断] 阅读进度链路统一日志,只记录,不参与任何业务判断。
 *
 * 目标:同一次会话内能串起 SAVE_READ → PERSIST → EXIT → RESTORE → POSITION_APPLY,
 * 区分"保存之前就已经回退"和"保存之后被旧状态覆盖"两种情况。
 *
 * 所有条目写入文件日志(externalCacheDir/logs,需开启"记录日志"),
 * 高频事件(如翻页保存)只写文件,避免冲掉应用内日志弹窗约 100 条的缓冲。
 */
object ReadProgressLog {

    fun log(
        phase: String,
        book: Book?,
        index: Int = -1,
        pos: Int = -1,
        title: String? = null,
        extra: String = "",
        intoAppLog: Boolean = true
    ) {
        if (!AppConfig.recordLog) return
        val msg = "[诊断]READ_PROGRESS $phase" +
                " name=${book?.name}" +
                " bookUrl=${book?.bookUrl}" +
                " origin=${book?.origin}" +
                " idx=$index pos=$pos" +
                " title=${title ?: book?.durChapterTitle}" +
                (if (extra.isEmpty()) "" else " $extra")
        LogUtils.d("READ_PROGRESS", msg)
        if (intoAppLog) {
            AppLog.putNotSave(msg)
        }
    }

}
