package io.legado.app.utils

import io.legado.app.help.globalExecutor
import java.util.logging.FileHandler
import java.util.logging.LogRecord

/**
 * 后台线程写盘的 FileHandler。
 *
 * [limit]/[count]/[append] 直传 `FileHandler(pattern, limit, count, append)`：
 * - append=true：同一天内应用重启不再截断既有日志（诊断日志要求长期保留）；
 * - limit>0 且 count>1：按大小分段滚动（pattern 需含 %g），单文件不会无限增长。
 */
class AsyncFileHandler(
    pattern: String,
    limit: Int = 0,
    count: Int = 1,
    append: Boolean = false,
) : FileHandler(pattern, limit, count, append) {

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        globalExecutor.execute {
            super.publish(record)
        }
    }

}
