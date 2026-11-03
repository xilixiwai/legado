package io.legado.app.help.book

import io.legado.app.data.entities.ReadRecordDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日统计拆天与恢复合并的纯逻辑测试（不触数据库、不触 Android 类）。
 */
class ReadRecordDetailHelperTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: String, time: String): Long {
        return LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()
    }

    private fun atIn(z: ZoneId, date: String, time: String): Long {
        return LocalDateTime.parse("${date}T$time").atZone(z).toInstant().toEpochMilli()
    }

    @Test
    fun `同日窗口拆为一段`() {
        val segments = ReadRecordDetailHelper.splitByDate(
            at("2026-11-04", "10:00"), at("2026-11-04", "11:30"), zone
        )
        assertEquals(1, segments.size)
        assertEquals("2026-11-04", segments[0].date)
        assertEquals(90 * 60 * 1000L, segments[0].segmentEnd - segments[0].segmentStart)
    }

    @Test
    fun `跨午夜窗口拆为两段且各段归属正确`() {
        val segments = ReadRecordDetailHelper.splitByDate(
            at("2026-11-04", "23:50"), at("2026-11-05", "00:10"), zone
        )
        assertEquals(2, segments.size)
        assertEquals("2026-11-04", segments[0].date)
        assertEquals(10 * 60 * 1000L, segments[0].segmentEnd - segments[0].segmentStart)
        assertEquals("2026-11-05", segments[1].date)
        assertEquals(10 * 60 * 1000L, segments[1].segmentEnd - segments[1].segmentStart)
        // 每段使用该日期段真实的起止时刻，而不是整段窗口
        assertEquals(at("2026-11-04", "23:50"), segments[0].segmentStart)
        assertEquals(at("2026-11-05", "00:00"), segments[1].segmentStart)
    }

    @Test
    fun `跨多天窗口按天拆分且总时长守恒`() {
        val start = at("2026-11-03", "22:00")
        val end = at("2026-11-06", "08:00")
        val segments = ReadRecordDetailHelper.splitByDate(start, end, zone)
        assertEquals(4, segments.size)
        assertEquals(listOf("2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06"), segments.map { it.date })
        val total = segments.sumOf { it.segmentEnd - it.segmentStart }
        assertEquals(end - start, total)
    }

    @Test
    fun `零或负窗口不产生分段`() {
        assertTrue(ReadRecordDetailHelper.splitByDate(1000L, 1000L, zone).isEmpty())
        assertTrue(ReadRecordDetailHelper.splitByDate(2000L, 1000L, zone).isEmpty())
    }

    @Test
    fun `DST 切换日午夜边界不崩溃且归属正确`() {
        // 美国 2026-11-01 02:00 结束夏令时（本地 11-01 当天有 25 小时）；
        // 时刻必须用目标时区构造，否则跨天判断无意义
        val dst = ZoneId.of("America/New_York")
        val start = atIn(dst, "2026-10-31", "23:50")
        val end = atIn(dst, "2026-11-01", "00:10")
        val segments = ReadRecordDetailHelper.splitByDate(start, end, dst)
        assertEquals(2, segments.size)
        assertEquals("2026-10-31", segments[0].date)
        assertEquals("2026-11-01", segments[1].date)
        val total = segments.sumOf { it.segmentEnd - it.segmentStart }
        assertEquals(end - start, total)
    }

    @Test
    fun `恢复合并且重复导入幂等`() {
        val old = ReadRecordDetail(
            deviceId = "dev", bookName = "书", bookAuthor = "作者", date = "2026-11-04",
            readTime = 600_000L, firstReadTime = 1_000L, lastReadTime = 600_000L, bookType = 8
        )
        val incoming = ReadRecordDetail(
            deviceId = "dev", bookName = "书", bookAuthor = "作者", date = "2026-11-04",
            readTime = 600_000L, firstReadTime = 1_000L, lastReadTime = 600_000L, bookType = 8
        )
        // 同一备份重复恢复：无变化
        assertFalse(ReadRecordDetailHelper.mergeRestored(old, incoming))
        assertEquals(600_000L, old.readTime)
    }

    @Test
    fun `恢复合并取较大时长与更早首次时间`() {
        val old = ReadRecordDetail(
            deviceId = "dev", bookName = "书", bookAuthor = "作者", date = "2026-11-04",
            readTime = 600_000L, firstReadTime = 5_000L, lastReadTime = 600_000L, bookType = 8
        )
        val incoming = ReadRecordDetail(
            deviceId = "dev", bookName = "书", bookAuthor = "作者", date = "2026-11-04",
            readTime = 900_000L, firstReadTime = 2_000L, lastReadTime = 800_000L, bookType = 8
        )
        assertTrue(ReadRecordDetailHelper.mergeRestored(old, incoming))
        assertEquals(900_000L, old.readTime)
        assertEquals(2_000L, old.firstReadTime)
        assertEquals(800_000L, old.lastReadTime)
    }

    @Test
    fun `每周从周一开始`() {
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        // 周三的周起始是同周周一
        assertEquals(monday, ReadRecordDetailHelper.weekStartOf(LocalDate.of(2026, 11, 4)))
        // 周一当天返回自身
        assertEquals(monday, ReadRecordDetailHelper.weekStartOf(monday))
        // 2026-11-01 是周日，其周起始是前一个周一 2026-10-26
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 11, 1).dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 26), ReadRecordDetailHelper.weekStartOf(LocalDate.of(2026, 11, 1)))
    }

}
