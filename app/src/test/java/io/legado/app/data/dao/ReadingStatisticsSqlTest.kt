package io.legado.app.data.dao

import io.legado.app.help.book.ReadRecordDetailHelper
import io.legado.app.help.book.ReadingSessionTimer
import io.legado.app.help.book.ReadingTimeIdentity
import io.legado.app.ui.statistics.dailyReadingAverage
import io.legado.app.utils.toReadDuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 真实 SQLite + Room 导出 schema + 生产聚合 SQL + 生产计时窗口的 JVM 回归。
 * 时间完全由测试推进，不等待真实一分钟。
 *
 * 写入适配器仅把生产窗口/拆天结果放入 SQLite，以验证聚合查询和数据单位。
 * 它不是 Room DAO，也不调用 ReadBook/ReadManga 的结算、回滚或冲正机制；
 * 这些机制的实际接线由独立 Android 测试覆盖，不能把本组结果当作设备验收。
 */
class ReadingStatisticsSqlTest {
    private lateinit var connection: Connection
    private val zone = ZoneId.of("Asia/Shanghai")
    private val base = at("2026-11-04T12:00:00")
    private val novel = ReadingTimeIdentity("小说", "作者", 0)
    private val manga = ReadingTimeIdentity("漫画", "作者", 8)

    @Before
    fun setUp() {
        connection = RealSqliteSupport.open(listOf("readRecord", "readRecordDetail"))
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun firstPageAtOneSecondThenExitAtSixtySecondsPersistsWholeMinute() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        timer.checkpoint(base + 1_000L)
        assertEquals(1_000L, detailTotal())
        timer.pause(base + 60_000L)
        assertEquals(60_000L, detailTotal())
        assertEquals(60_000L, cumulative(novel.name))
        assertEquals("1分钟", detailTotal().toReadDuration())
    }

    @Test
    fun ninetySecondsWithoutPageChangesIsFlushedOnExit() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        timer.pause(base + 90_000L)
        assertEquals(90_000L, detailTotal())
        assertEquals(90_000L, cumulative(novel.name))
        assertEquals("1分钟30秒", detailTotal().toReadDuration())
    }

    @Test
    fun durationsBelowOneMinutePreserveMilliseconds() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        timer.pause(base + 12_345L)
        assertEquals(12_345L, detailTotal())
        assertEquals(12_345L, cumulative(novel.name))
        assertEquals("12秒", detailTotal().toReadDuration())
    }

    @Test
    fun periodicAndPageCheckpointsAndExitDoNotCountSameWindowTwice() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        listOf(15_000L, 30_000L, 30_000L, 45_000L, 60_000L, 75_000L).forEach {
            timer.checkpoint(base + it)
        }
        timer.pause(base + 90_000L)
        timer.pause(base + 90_000L)
        timer.checkpoint(base + 120_000L)
        assertEquals(90_000L, detailTotal())
        assertEquals(90_000L, cumulative(novel.name))
    }

    @Test
    fun switchingBooksSettlesPreviousIdentityAndStartsNewWindow() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        timer.bind(manga, base + 60_000L)
        timer.pause(base + 150_000L)
        assertEquals(60_000L, cumulative(novel.name))
        assertEquals(90_000L, cumulative(manga.name))
        assertEquals(150_000L, detailTotal())
        assertEquals(listOf("漫画" to 90_000L, "小说" to 60_000L), ranks("2026-11-04", "2026-11-05"))
    }

    @Test
    fun backgroundAndLockScreenTimeIsExcludedAfterResume() {
        val timer = session()
        timer.bind(novel, base)
        timer.resume(base)
        timer.pause(base + 30_000L)
        timer.checkpoint(base + 120_000L)
        timer.resume(base + 330_000L)
        timer.pause(base + 390_000L)
        assertEquals(90_000L, detailTotal())
        assertEquals(90_000L, cumulative(novel.name))
    }

    @Test
    fun queuedEventsUseCapturedEventTimeRatherThanLateExecutionTime() {
        val timer = session()
        var clock = base
        val queued = mutableListOf<() -> Unit>()
        timer.bind(novel, clock)
        val resumedAt = clock
        queued += { timer.resume(resumedAt) }
        clock += 60_000L
        val pausedAt = clock
        queued += { timer.pause(pausedAt) }
        clock += 600_000L
        queued.forEach { it() }
        assertEquals(base + 660_000L, clock)
        assertEquals(60_000L, detailTotal())
    }

    @Test
    fun midnightAndMondayBoundaryUseLocalDateAndConserveDuration() {
        val start = at("2026-11-01T23:59:30")
        val timer = session()
        timer.bind(novel, start)
        timer.resume(start)
        timer.pause(start + 60_000L)
        assertEquals(30_000L, sumRange("2026-11-01", "2026-11-02"))
        assertEquals(30_000L, sumRange("2026-11-02", "2026-11-03"))
        val monday = ReadRecordDetailHelper.weekStartOf(LocalDate.of(2026, 11, 4))
        assertEquals(LocalDate.of(2026, 11, 2), monday)
        assertEquals(30_000L, sumRange(monday.toString(), monday.plusWeeks(1).toString()))
        assertEquals(60_000L, detailTotal())
        assertEquals(2L, scalarLong(ReadStatisticsSql.DISTINCT_DATE_COUNT))
        assertEquals(30_000L, dailyReadingAverage(detailTotal(), 2))
    }

    @Test
    fun monthBoundaryDoesNotMoveOctoberTimeIntoNovember() {
        val start = at("2026-10-31T23:59:30")
        val timer = session()
        timer.bind(novel, start)
        timer.resume(start)
        timer.pause(start + 60_000L)
        assertEquals(30_000L, sumRange("2026-10-01", "2026-11-01"))
        assertEquals(30_000L, sumRange("2026-11-01", "2026-12-01"))
        assertEquals(60_000L, cumulative(novel.name))
    }

    @Test
    fun overviewWeekMonthAverageAndSevenDayBarsMatchSameRows() {
        val first = LocalDate.of(2026, 10, 29)
        (0L..6L).forEach { day ->
            val start = first.plusDays(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            writeWindow(novel, start, start + (day + 1) * 10_000L)
        }
        val days = (0L..6L).map { offset ->
            val date = first.plusDays(offset)
            sumRange(date.toString(), date.plusDays(1).toString())
        }
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L, 70_000L), days)
        assertEquals(280_000L, days.sum())
        assertEquals(days.sum(), detailTotal())
        assertEquals(180_000L, sumRange("2026-11-02", "2026-11-09"))
        assertEquals(220_000L, sumRange("2026-11-01", "2026-12-01"))
        assertEquals(7L, scalarLong(ReadStatisticsSql.DISTINCT_DATE_COUNT))
        assertEquals(40_000L, dailyReadingAverage(detailTotal(), 7))
        assertEquals(280_000L, cumulative(novel.name))
    }

    @Test
    fun historicalCumulativeTimeDoesNotFabricateDailyHistory() {
        connection.exec(
            "insert into readRecord(deviceId,bookName,readTime,lastRead) values('',:name,499000,0)",
            mapOf("name" to novel.name)
        )
        writeWindow(novel, base, base + 1_000L)
        assertEquals(500_000L, cumulative(novel.name))
        assertEquals(1_000L, detailTotal())
        assertEquals(1_000L, sumRange("2026-11-02", "2026-11-09"))
        assertEquals(1_000L, dailyReadingAverage(detailTotal(), 1))
    }

    @Test
    fun devicePartitionsStayIndependentWhileDisplayQueriesSumBoth() {
        writeWindow(novel, base, base + 60_000L)
        writeWindow(novel, base, base + 90_000L, device = "foreign", cumulativeDevice = "foreign")
        assertEquals(60_000L, cumulative(novel.name))
        assertEquals(90_000L, cumulative(novel.name, "foreign"))
        assertEquals(2L, scalarLong("select count(*) from readRecordDetail"))
        assertEquals(1L, scalarLong(ReadStatisticsSql.DISTINCT_DATE_COUNT))
        assertEquals(150_000L, sumRange("2026-11-04", "2026-11-05"))
        assertEquals(listOf(novel.name to 150_000L), ranks("2026-11-04", "2026-11-05"))
    }

    @Test
    fun dailyRankKeepsAuthorsSeparateWithoutChangingLegacyNameGrouping() {
        writeWindow(novel, base, base + 60_000L)
        writeWindow(novel.copy(author = "另一作者"), base, base + 90_000L)
        assertEquals(2L, scalarLong("select count(*) from readRecordDetail"))
        assertEquals(2, ranks("2026-11-04", "2026-11-05").size)
        assertEquals(150_000L, cumulative(novel.name))
        assertEquals(150_000L, detailTotal())
    }

    @Test
    fun emptyDatabaseAndHalfOpenRangeDoNotInventData() {
        assertNull(scalarLong(ReadStatisticsSql.TOTAL_SUM))
        assertEquals(0L, scalarLong(ReadStatisticsSql.DISTINCT_DATE_COUNT))
        assertEquals(0L, sumRange("2026-11-04", "2026-11-05"))
        writeWindow(novel, at("2026-11-05T00:00:00"), at("2026-11-05T00:01:00"))
        assertEquals(0L, sumRange("2026-11-04", "2026-11-05"))
        assertEquals(60_000L, sumRange("2026-11-05", "2026-11-06"))
    }

    private fun session() = ReadingSessionTimer<ReadingTimeIdentity>(
        onIdentityChanged = {},
        onWindow = { identity, start, end -> writeWindow(identity, start, end) }
    )

    private fun at(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private fun detailTotal(): Long = scalarLong(ReadStatisticsSql.TOTAL_SUM) ?: 0L

    private fun cumulative(name: String, device: String = ""): Long = scalarLong(
        "select readTime from readRecord where deviceId = :device and bookName = :name",
        mapOf("device" to device, "name" to name)
    ) ?: 0L

    private fun sumRange(start: String, end: String): Long = scalarLong(
        ReadStatisticsSql.SUM_RANGE, mapOf("start" to start, "end" to end)
    ) ?: 0L

    private fun scalarLong(sql: String, args: Map<String, String> = emptyMap()): Long? {
        val (query, order) = RealSqliteSupport.toPositional(sql, args)
        connection.prepareStatement(query).use { statement ->
            order.forEachIndexed { index, name -> statement.setString(index + 1, args.getValue(name)) }
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val value = result.getLong(1)
                return if (result.wasNull()) null else value
            }
        }
    }

    private fun ranks(start: String, end: String): List<Pair<String, Long>> {
        val args = mapOf("start" to start, "end" to end)
        val (query, order) = RealSqliteSupport.toPositional(ReadStatisticsSql.RANK_RANGE, args)
        connection.prepareStatement(query).use { statement ->
            order.forEachIndexed { index, name -> statement.setString(index + 1, args.getValue(name)) }
            statement.executeQuery().use { result ->
                val rows = mutableListOf<Pair<String, Long>>()
                while (result.next()) rows += result.getString("bookName") to result.getLong("readTime")
                return rows
            }
        }
    }

    /** Test-only JDBC adapter: window generation and date splitting above are production code. */
    private fun writeWindow(
        identity: ReadingTimeIdentity,
        start: Long,
        end: Long,
        device: String = "local",
        cumulativeDevice: String = ""
    ) {
        val segments = ReadRecordDetailHelper.splitByDate(start, end, zone)
        if (segments.isEmpty()) return
        connection.autoCommit = false
        try {
            val args = mapOf(
                "device" to cumulativeDevice, "name" to identity.name,
                "duration" to (end - start).toString(), "end" to end.toString()
            )
            connection.exec(
                "insert or ignore into readRecord(deviceId,bookName,readTime,lastRead) values(:device,:name,0,0)",
                args
            )
            connection.exec(
                "update readRecord set readTime = readTime + :duration, lastRead = :end " +
                    "where deviceId = :device and bookName = :name", args
            )
            for (segment in segments) {
                val detailArgs = mapOf(
                    "device" to device, "name" to identity.name, "author" to identity.author,
                    "date" to segment.date, "type" to identity.type.toString(),
                    "start" to segment.segmentStart.toString(), "end" to segment.segmentEnd.toString(),
                    "duration" to (segment.segmentEnd - segment.segmentStart).toString()
                )
                connection.exec(
                    """insert or ignore into readRecordDetail
                        (deviceId,bookName,bookAuthor,date,readTime,firstReadTime,lastReadTime,bookType)
                        values(:device,:name,:author,:date,0,:start,:end,:type)""", detailArgs
                )
                connection.exec(
                    """update readRecordDetail set readTime = readTime + :duration,
                        firstReadTime = min(firstReadTime, :start), lastReadTime = max(lastReadTime, :end)
                        where deviceId = :device and bookName = :name and bookAuthor = :author and date = :date""",
                    detailArgs
                )
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }
}
