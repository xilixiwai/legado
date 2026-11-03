package io.legado.app.help.book

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 每日阅读统计写入与清理。
 *
 * - 只追加每日明细（readRecordDetail），不改变 readRecord 累计表语义；
 * - 跨本地午夜的阅读窗口按自然日拆分，每个日期段记录真实的起止时刻；
 * - 所有写入都发生在调用方的事务内（与累计表写入同事务），失败一起回滚；
 * - 清空操作与计时写入共用同一个单线程执行器（globalExecutor），串行执行，
 *   并在清空事务内重置阅读模型单例的内存缓存与计时窗口起点，
 *   保证已排队/进行中的旧窗口不会在清空后把旧数据写回。
 */
object ReadRecordDetailHelper {

    /** 拆天结果：本地日期键、该段真实起止时刻 */
    data class DateSegment(val date: String, val segmentStart: Long, val segmentEnd: Long)

    /**
     * 每周从周一开始：返回 date 所在周的周一（含当天为周一的情形）。
     * 纯函数，周统计口径统一收敛于此。
     */
    fun weekStartOf(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /**
     * 把阅读窗口 [windowStart, windowEnd) 按本地时区自然日切分。
     * 窗口非法（非正时长）返回空列表。
     * 纯函数，便于单元测试（zone 可注入）。
     */
    fun splitByDate(
        windowStart: Long,
        windowEnd: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<DateSegment> {
        if (windowEnd <= windowStart) return emptyList()
        val segments = ArrayList<DateSegment>(2)
        var cursor = windowStart
        while (cursor < windowEnd) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(windowEnd, nextMidnight)
            segments.add(DateSegment(date.toString(), cursor, segmentEnd))
            cursor = segmentEnd
        }
        return segments
    }

    /**
     * 把一个已结算的阅读窗口写入每日明细。
     * 必须在包含累计表写入的同一数据库事务内调用。
     * book 为空（理论上不发生）或窗口非法时跳过，不影响累计表。
     */
    fun appendWindow(book: Book?, windowStart: Long, windowEnd: Long) {
        book ?: return
        val segments = splitByDate(windowStart, windowEnd)
        if (segments.isEmpty()) return
        val dao = appDb.readRecordDetailDao
        for (segment in segments) {
            val row = dao.get(AppConst.androidId, book.name, book.author, segment.date)
            if (row == null) {
                dao.insert(
                    ReadRecordDetail(
                        deviceId = AppConst.androidId,
                        bookName = book.name,
                        bookAuthor = book.author,
                        date = segment.date,
                        readTime = segment.segmentEnd - segment.segmentStart,
                        firstReadTime = segment.segmentStart,
                        lastReadTime = segment.segmentEnd,
                        bookType = book.type
                    )
                )
            } else {
                row.readTime += segment.segmentEnd - segment.segmentStart
                if (row.firstReadTime <= 0 || segment.segmentStart < row.firstReadTime) {
                    row.firstReadTime = segment.segmentStart
                }
                if (segment.segmentEnd > row.lastReadTime) {
                    row.lastReadTime = segment.segmentEnd
                }
                row.bookType = book.type
                dao.update(row)
            }
        }
    }

    /**
     * 恢复备份时的幂等合并：同键取较大值。
     * readTime 取 max（重复恢复不翻倍）；firstReadTime 取非零较小值；lastReadTime 取 max。
     * 返回 true 表示有更新。
     */
    fun mergeRestored(old: ReadRecordDetail, incoming: ReadRecordDetail): Boolean {
        var changed = false
        if (incoming.readTime > old.readTime) {
            old.readTime = incoming.readTime
            changed = true
        }
        if (incoming.firstReadTime in 1 until old.firstReadTime || old.firstReadTime <= 0) {
            old.firstReadTime = incoming.firstReadTime
            changed = true
        }
        if (incoming.lastReadTime > old.lastReadTime) {
            old.lastReadTime = incoming.lastReadTime
            changed = true
        }
        return changed
    }

    /**
     * 身份迁移时的同键合并：同一本书换源前后是同一段连续阅读，时长**求和**而非取较大值
     * （与恢复合并取 max 的幂等语义不同：迁移只会发生一次，求和才不丢时间）。
     * firstReadTime 取非零较小值；lastReadTime 取 max；bookType 保持 target 现值。
     */
    fun mergeMigrated(target: ReadRecordDetail, incoming: ReadRecordDetail) {
        target.readTime += incoming.readTime
        if (incoming.firstReadTime in 1 until target.firstReadTime || target.firstReadTime <= 0) {
            target.firstReadTime = incoming.firstReadTime
        }
        if (incoming.lastReadTime > target.lastReadTime) {
            target.lastReadTime = incoming.lastReadTime
        }
    }

    /** 累计表迁移合并：同设备同书名是同一段连续阅读，时长求和，lastRead 取 max。 */
    fun mergeMigrated(target: io.legado.app.data.entities.ReadRecord, incoming: io.legado.app.data.entities.ReadRecord) {
        target.readTime += incoming.readTime
        if (incoming.lastRead > target.lastRead) {
            target.lastRead = incoming.lastRead
        }
    }

    /**
     * 书籍身份迁移：换源/重命名导致 (bookName, bookAuthor) 变化时，
     * 把本机的阅读统计迁到新身份下，保证同一本书的统计连续。
     *
     * - 每日明细：迁移本机分区（deviceId = androidId）的全部日期行，按日期撞键求和合并；
     * - 累计表：本机历史行 deviceId 为空串，仅在书名变化时迁移（累计表按书名存储），
     *   撞键求和合并；其他设备分区的行不迁移（那是其他设备的书籍副本）。
     * - 全程独立事务，失败只记日志，不影响换源主流程（等同于保持旧行为）。
     * - db 参数可注入（默认 appDb），供 androidTest 使用内存库验证。
     */
    fun migrateBookIdentity(
        oldName: String,
        oldAuthor: String,
        newName: String,
        newAuthor: String,
        db: io.legado.app.data.AppDatabase = appDb
    ) {
        if (oldName == newName && oldAuthor == newAuthor) return
        runCatching {
            db.runInTransaction {
                val detailDao = db.readRecordDetailDao
                val oldRows = detailDao.getByBook(AppConst.androidId, oldName, oldAuthor)
                for (oldRow in oldRows) {
                    val target = detailDao.get(
                        AppConst.androidId, newName, newAuthor, oldRow.date
                    )
                    if (target == null) {
                        detailDao.insert(
                            oldRow.copy(bookName = newName, bookAuthor = newAuthor)
                        )
                    } else {
                        mergeMigrated(target, oldRow)
                        detailDao.update(target)
                    }
                    detailDao.delete(oldRow)
                }
                if (oldName != newName) {
                    val recordDao = db.readRecordDao
                    // 本机历史累计行的 deviceId 为空串（ReadBook/ReadManga 的默认值）
                    val oldRecord = recordDao.get("", oldName)
                    if (oldRecord != null) {
                        val target = recordDao.get("", newName)
                        if (target == null) {
                            oldRecord.bookName = newName
                            recordDao.insert(oldRecord)
                        } else {
                            mergeMigrated(target, oldRecord)
                            recordDao.update(target)
                        }
                        recordDao.delete(oldRecord)
                    }
                }
            }
        }.onFailure {
            AppLog.put("书籍身份迁移阅读统计失败:${it.localizedMessage}", it)
        }
    }

    /**
     * 清空全部阅读统计（累计表 + 每日明细表），完成后在回调返回结果。
     *
     * 必须与计时写入在同一个单线程执行器（globalExecutor）上排队执行：
     * FIFO 保证此前已排队的结算先落库、随后被本次清空一并清除；
     * 排队中的结算在执行时读取到已重锚的窗口起点，只会写入清空之后的阅读。
     * 事务内同时重置阅读模型单例的内存累计缓存，防止下次结算把清空前的旧值整段写回。
     *
     * @param callback 在执行器线程回调，true 表示清空成功并已复核两表为空；
     *                 失败时调用方须明确提示，不得显示虚假成功状态。
     */
    fun clearAllStatistics(callback: (Boolean) -> Unit) {
        ReadBook.executor.execute {
            val ok = runCatching {
                appDb.runInTransaction {
                    appDb.readRecordDao.clear()
                    appDb.readRecordDetailDao.clear()
                    ReadBook.resetReadRecordCache()
                    ReadManga.resetReadRecordCache()
                }
                // 复核两张表确已清空（防异常路径部分写入）
                appDb.readRecordDao.allTime == 0L && appDb.readRecordDetailDao.all.isEmpty()
            }.getOrElse { false }
            callback(ok)
        }
    }
}
