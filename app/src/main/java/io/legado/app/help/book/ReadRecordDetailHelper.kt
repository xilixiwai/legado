package io.legado.app.help.book

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadManga
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 每日阅读统计写入、身份迁移与清理。
 *
 * - 只追加每日明细（readRecordDetail），不改变 readRecord 累计表语义；
 * - 跨本地午夜的阅读窗口按自然日拆分，每个日期段记录真实的起止时刻；
 * - 所有写入都发生在调用方的事务内（与累计表写入同事务），失败一起回滚；
 * - 清空操作与计时写入共用同一个单线程执行器（globalExecutor），串行执行，
 *   并在清空事务内重置阅读模型单例的内存缓存与计时窗口起点，
 *   保证已排队/进行中的旧窗口不会在清空后把旧数据写回；
 * - 事务失败的计时窗口进入待重试队列（[pendingStats]），由后续成功的结算或换源流程
 *   在同一事务内冲正累计表并补写每日明细，保证单次事务失败不造成两表永久不一致。
 *
 * 并发约定：本对象的队列、重定向表等可变状态只在 globalExecutor 线程读写
 * （计时结算、换源迁移、清空全部在该队列上串行）；androidTest 可在测试线程
 * 传入独立内存库调用，此时无并发。
 */
object ReadRecordDetailHelper {

    /** 拆天结果：本地日期键、该段真实起止时刻 */
    data class DateSegment(val date: String, val segmentStart: Long, val segmentEnd: Long)

    /**
     * 待冲正的计时窗口（事务失败的结算）。
     * 保存窗口产生时的书籍身份快照；冲正写入时再按身份重定向解析到最终身份。
     */
    data class PendingStat(
        val bookName: String,
        val bookAuthor: String,
        val bookType: Int,
        val windowStart: Long,
        val windowEnd: Long
    )

    private val pendingStats = ArrayDeque<PendingStat>()
    private val pendingMigrations = ArrayDeque<Array<String>>()
    private val identityRedirects = HashMap<Pair<String, String>, Pair<String, String>>()

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
     * 把一个已结算的阅读窗口写入每日明细（本机分区）。
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
     * 必须在 globalExecutor 线程调用（与所有结算/迁移/清空串行）。
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
     * 重试待冲正窗口与待重试身份迁移。
     * - 每个待冲正窗口在单个事务内完成：累计表本机分区补差（内存累计已含该窗口，
     *   落库收敛，不会重复累计）+ 每日明细补写，写入时按身份重定向解析到最终身份；
     * - 成功才出队，失败保留等待下次重试；数据库持续不可用时保持队列原样；
     * - 迁移重试在窗口冲正之后执行，保证冲正落库的旧身份数据一并迁走。
     */
    fun retryPendingWork(db: io.legado.app.data.AppDatabase = appDb) {
        for (entry in pendingStats.toList()) {
            val ok = runCatching {
                val (finalName, finalAuthor) =
                    resolveMigratedIdentity(entry.bookName, entry.bookAuthor)
                db.runInTransaction {
                    appendWindow(
                        finalName, finalAuthor, entry.bookType,
                        entry.windowStart, entry.windowEnd, db
                    )
                    val delta = entry.windowEnd - entry.windowStart
                    val recordDao = db.readRecordDao
                    val existing = recordDao.get("", finalName)
                    if (existing == null) {
                        recordDao.insert(
                            ReadRecord(
                                deviceId = "", bookName = finalName,
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
                pendingStats.remove(entry)
            } else {
                // 数据库仍不可用：保留队列，等待下次结算成功后再试
                return
            }
        }
        for (migration in pendingMigrations.toList()) {
            if (doMigrateBookIdentity(migration[0], migration[1], migration[2], migration[3], db)) {
                pendingMigrations.remove(migration)
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
    fun mergeMigrated(target: ReadRecord, incoming: ReadRecord) {
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
     *   撞键求和合并；其他设备分区的行不迁移（那是其他设备的书籍副本）；
     * - 迁移以"搬走旧行"实现：源键行迁空后再次执行是空操作，天然幂等；
     *   事务原子，中途失败整体回滚，重跑不会重复求和；
     * - 成功后注册身份重定向（[identityRedirects]），此后持有旧书籍对象的结算
     *   与待冲正窗口都会自动归入新身份，旧身份不再接收新计时；
     * - 失败时旧行原样保留（数据不丢失），并入重试队列随后续结算重试；
     * - db 参数可注入（默认 appDb），供 androidTest 使用内存库验证。
     */
    fun migrateBookIdentity(
        oldName: String,
        oldAuthor: String,
        newName: String,
        newAuthor: String,
        db: io.legado.app.data.AppDatabase = appDb
    ) {
        if (!doMigrateBookIdentity(oldName, oldAuthor, newName, newAuthor, db)) {
            pendingMigrations.addLast(arrayOf(oldName, oldAuthor, newName, newAuthor))
            AppLog.put("书籍身份迁移失败待重试:$oldName → $newName")
        }
    }

    private fun doMigrateBookIdentity(
        oldName: String,
        oldAuthor: String,
        newName: String,
        newAuthor: String,
        db: io.legado.app.data.AppDatabase
    ): Boolean {
        if (oldName == newName && oldAuthor == newAuthor) return true
        val ok = runCatching {
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
        }.isSuccess
        if (ok) {
            registerIdentityRedirect(oldName, oldAuthor, newName, newAuthor)
        }
        return ok
    }

    /**
     * 注册身份重定向：旧身份的后续计时与待冲正窗口改记到新身份。
     * 链式迁移（A→B 后又 B→C）直接注册最终身份，解析无需递归。
     */
    internal fun registerIdentityRedirect(
        oldName: String,
        oldAuthor: String,
        newName: String,
        newAuthor: String
    ) {
        if (oldName == newName && oldAuthor == newAuthor) return
        identityRedirects[Pair(oldName, oldAuthor)] =
            resolveMigratedIdentity(newName, newAuthor)
    }

    /**
     * 解析书籍的当前统计身份：若（或其祖先键）已迁移，返回最终 (bookName, bookAuthor)。
     */
    fun resolveMigratedIdentity(name: String, author: String): Pair<String, String> {
        var key = Pair(name, author)
        var guard = 0
        while (guard++ < 16) {
            val next = identityRedirects[key] ?: break
            key = next
        }
        return key
    }

    /**
     * 清空全部阅读统计（累计表 + 每日明细表），完成后在回调返回结果。
     *
     * 必须与计时写入在同一个单线程执行器（globalExecutor）上排队执行：
     * FIFO 保证此前已排队的结算先落库、随后被本次清空一并清除；
     * 排队中的结算在执行时读取到已重锚的窗口起点，只会写入清空之后的阅读。
     * 事务内同时重置阅读模型单例的内存累计缓存、丢弃待冲正窗口与待重试迁移，
     * 防止清空前的旧数据（含事务失败未落库的窗口）在清空后写回。
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
                    pendingStats.clear()
                    pendingMigrations.clear()
                }
                // 复核两张表确已清空（防异常路径部分写入）
                appDb.readRecordDao.allTime == 0L && appDb.readRecordDetailDao.all.isEmpty()
            }.getOrElse { false }
            callback(ok)
        }
    }
}
