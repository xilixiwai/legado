package io.legado.app.help.book

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 每日阅读统计写入与清理。
 *
 * 身份口径（轻量原则）：书籍身份 = (bookName, bookAuthor)。
 * 能匹配到已有统计身份就继续累计原记录；匹配不到就创建新记录；
 * 换源等场景导致身份变化时新旧记录各自保留，不做自动找回或合并，
 * 也不根据书名相似度等不可靠条件强行归并。
 *
 * - 只追加每日明细（readRecordDetail），不改变 readRecord 累计表语义；
 * - 跨本地午夜的阅读窗口按自然日拆分，每个日期段记录真实的起止时刻；
 * - 所有写入都发生在调用方的事务内（与累计表写入同事务），失败一起回滚；
 * - 清空操作与计时写入共用同一个单线程执行器（globalExecutor），串行执行，
 *   并在清空提交后重置阅读模型单例的内存缓存与计时窗口起点，
 *   保证已排队/进行中的旧窗口不会在清空后把旧数据写回；
 * - 事务失败的计时窗口撤销内存推进后进入待冲正队列（[pendingStats]），
 *   由后续成功的结算在同一事务内补写每日明细并补齐累计表，并把时长同步回
 *   当前活动书籍的内存累计，保证每个窗口只入账一次、两表与内存最终一致。
 *
 * 并发约定：本对象的队列等可变状态只在 globalExecutor 线程读写
 * （计时结算、清空全部在该队列上串行）；androidTest 可在测试线程
 * 传入独立内存库调用，此时无并发。
 */
object ReadRecordDetailHelper {

    /** 统计页只展示持久化值：等待此前的离页/切书结算，不把实时会话虚加到查询结果。 */
    suspend fun awaitPendingWrites() = suspendCancellableCoroutine<Unit> { continuation ->
        ReadBook.executor.execute {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    /** 拆天结果：本地日期键、该段真实起止时刻 */
    data class DateSegment(val date: String, val segmentStart: Long, val segmentEnd: Long)

    /**
     * 待冲正的计时窗口（事务失败的结算）。
     * 保存窗口产生时的书籍身份快照与真实起止时刻。
     */
    data class PendingStat(
        val bookName: String,
        val bookAuthor: String,
        val bookType: Int,
        val windowStart: Long,
        val windowEnd: Long
    )

    private val pendingStats = ArrayDeque<PendingStat>()

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
     * 把一个已结算的阅读窗口写入每日明细（本机分区）：
     * 该身份当日已有记录则累计，没有则创建新记录。
     * 必须在包含累计表写入的同一数据库事务内调用；窗口非法时跳过。
     * db 可注入供 androidTest 使用内存库验证。
     */
    fun appendWindow(
        bookName: String,
        bookAuthor: String,
        bookType: Int,
        windowStart: Long,
        windowEnd: Long,
        db: io.legado.app.data.AppDatabase = appDb
    ) {
        val segments = splitByDate(windowStart, windowEnd)
        if (segments.isEmpty()) return
        val dao = db.readRecordDetailDao
        for (segment in segments) {
            val row = dao.get(AppConst.androidId, bookName, bookAuthor, segment.date)
            if (row == null) {
                dao.insert(
                    ReadRecordDetail(
                        deviceId = AppConst.androidId,
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        date = segment.date,
                        readTime = segment.segmentEnd - segment.segmentStart,
                        firstReadTime = segment.segmentStart,
                        lastReadTime = segment.segmentEnd,
                        bookType = bookType
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
                row.bookType = bookType
                dao.update(row)
            }
        }
    }

    /**
     * 事务失败的结算窗口入队等待冲正。
     * 必须在 globalExecutor 线程调用（与所有结算/清空串行）。
     * 队列仅在内存保存：进程死亡时内存累计与待冲正窗口一同消失，
     * 两表都回到最近一次成功结算的状态，不存在单表多计的不一致。
     */
    fun enqueuePendingStat(
        bookName: String,
        bookAuthor: String,
        bookType: Int,
        windowStart: Long,
        windowEnd: Long
    ) {
        if (windowEnd <= windowStart) return
        pendingStats.addLast(
            PendingStat(bookName, bookAuthor, bookType, windowStart, windowEnd)
        )
    }

    /**
     * 冲正待冲正窗口：每个窗口在单个事务内完成每日明细补写 + 累计表本机分区补差；
     * 成功才出队，失败整体回滚并保留队列等待下次重试，数据库持续不可用时保持队列原样。
     * 结算失败时内存累计已撤销该窗口（见调用方），累计表确实缺少该窗口时长，
     * 补差不会重复入账；补差提交后若该书正被阅读，同步补入其内存累计，
     * 防止该书下一次结算的整行 REPLACE 丢掉刚补入数据库的时长。
     */
    fun retryPendingWork(db: io.legado.app.data.AppDatabase = appDb) {
        for (entry in pendingStats.toList()) {
            val delta = entry.windowEnd - entry.windowStart
            val ok = runCatching {
                db.runInTransaction {
                    appendWindow(
                        entry.bookName, entry.bookAuthor, entry.bookType,
                        entry.windowStart, entry.windowEnd, db
                    )
                    val recordDao = db.readRecordDao
                    val existing = recordDao.get("", entry.bookName)
                    if (existing == null) {
                        recordDao.insert(
                            ReadRecord(
                                deviceId = "", bookName = entry.bookName,
                                readTime = delta, lastRead = entry.windowEnd
                            )
                        )
                    } else {
                        existing.readTime += delta
                        if (entry.windowEnd > existing.lastRead) {
                            existing.lastRead = entry.windowEnd
                        }
                        recordDao.update(existing)
                    }
                }
                true
            }.getOrElse {
                AppLog.put("阅读统计冲正失败:${it.localizedMessage}", it)
                false
            }
            if (ok) {
                // 事务已提交：若该窗口属于当前活动书籍，把时长同步补入其内存累计，
                // 否则该书下一次结算的 REPLACE 会以未含该窗口的内存覆盖数据库
                ReadBook.addReconciledReadTime(entry.bookName, delta)
                ReadManga.addReconciledReadTime(entry.bookName, delta)
                pendingStats.remove(entry)
            } else {
                // 数据库仍不可用：保留队列、不触碰内存，等待下次结算成功后再试
                return
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
     * 清空全部阅读统计（累计表 + 每日明细表），完成后在回调返回结果。
     *
     * 必须与计时写入在同一个单线程执行器（globalExecutor）上排队执行：
     * FIFO 保证此前已排队的结算先落库、随后被本次清空一并清除；
     * 排队中的结算在执行时读取到已重锚的窗口起点，只会写入清空之后的阅读。
     *
     * 数据库清空提交并复核通过后，才在同一执行器任务内重置内存累计缓存、
     * 丢弃待冲正窗口（整个任务期间无其他任务可交错，串行等价于事务内重置）。
     * 若清空事务失败或复核失败：内存累计、待冲正窗口全部原样保留，
     * 数据库保持最近一次成功状态——后续结算与冲正继续收敛，旧数据既不复活也不丢失。
     *
     * @param callback 在执行器线程回调，true 表示清空成功并已复核两表为空；
     *                 失败时调用方须明确提示，不得显示虚假成功状态。
     */
    fun clearAllStatistics(
        db: io.legado.app.data.AppDatabase = appDb,
        callback: (Boolean) -> Unit
    ) {
        ReadBook.executor.execute {
            val ok = runCatching {
                db.runInTransaction {
                    db.readRecordDao.clear()
                    db.readRecordDetailDao.clear()
                }
                // 复核两表确已清空（防异常路径部分写入）
                check(db.readRecordDao.allTime == 0L && db.readRecordDetailDao.all.isEmpty())
                ReadBook.resetReadRecordCache()
                ReadManga.resetReadRecordCache()
                pendingStats.clear()
                true
            }.getOrElse { false }
            callback(ok)
        }
    }
}
