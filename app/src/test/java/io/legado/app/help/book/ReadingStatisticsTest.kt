package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.utils.toReadDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读统计聚合回归测试(纯函数,不访问数据库、不依赖 Android 框架)。
 *
 * 覆盖:空书架/无记录 / 单书单记录 / 多书多记录 / 同书多条历史记录聚合 /
 * 未读-阅读中-已读完状态 / 时长排序 / 最近阅读排序 / 记录书已下架 /
 * 进度不可计算(不伪造) / 截断边界 / 入参不可变 / 时长格式化。
 */
class ReadingStatisticsTest {

    private fun book(
        name: String,
        author: String = "author",
        origin: String = "origin",
        durChapterIndex: Int = 0,
        durChapterPos: Int = 0,
        totalChapterNum: Int = 0,
        coverUrl: String? = null
    ) = Book(bookUrl = "url://$name", name = name, author = author, origin = origin).apply {
        this.durChapterIndex = durChapterIndex
        this.durChapterPos = durChapterPos
        this.totalChapterNum = totalChapterNum
        this.coverUrl = coverUrl
    }

    private fun show(name: String, readTime: Long, lastRead: Long) =
        ReadRecordShow(name, readTime, lastRead)

    private fun row(deviceId: String, name: String, readTime: Long, lastRead: Long) =
        ReadRecord(deviceId = deviceId, bookName = name, readTime = readTime, lastRead = lastRead)

    // 1. 空书架 + 无记录:全部为 0,不崩溃
    @Test
    fun `empty shelf and no records produce empty statistics`() {
        val stat = buildReadingStatistics(emptyList(), emptyList(), emptyList(), 0L)
        assertEquals(0, stat.totalBookCount)
        assertEquals(0, stat.unreadCount)
        assertEquals(0, stat.readingCount)
        assertEquals(0, stat.finishedCount)
        assertEquals(0L, stat.totalReadTime)
        assertEquals(0, stat.estimatedReadChapters)
        assertFalse(stat.hasDuplicateRecords)
        assertTrue(stat.items.isEmpty())
        assertTrue(stat.rankByReadTime.isEmpty())
        assertTrue(stat.recentRead.isEmpty())
    }

    // 2. 单书单记录
    @Test
    fun `single book with a single record`() {
        val b = book(
            "A", durChapterIndex = 4, durChapterPos = 10,
            totalChapterNum = 10, coverUrl = "cover://A"
        )
        val stat = buildReadingStatistics(
            books = listOf(b),
            recordShows = listOf(show("A", 60_000L, 1_000L)),
            recordRows = listOf(row("", "A", 60_000L, 1_000L)),
            totalReadTime = 60_000L
        )
        assertEquals(1, stat.totalBookCount)
        assertEquals(1, stat.readingCount)
        assertEquals(0, stat.unreadCount)
        assertEquals(0, stat.finishedCount)
        assertEquals(60_000L, stat.totalReadTime)
        assertFalse(stat.hasDuplicateRecords)
        val item = stat.items.single()
        assertEquals("A", item.bookName)
        assertEquals("author", item.author)
        assertEquals("origin", item.origin)
        assertEquals("cover://A", item.coverUrl)
        assertTrue(item.inBookshelf)
        assertFalse(item.unread)
        assertTrue(item.reading)
        assertFalse(item.finished)
        assertEquals(5, item.readChapterCount)
        assertEquals(50, item.progressPercent)
    }

    // 3. 多书 + 多记录,并区分已读/阅读中/已读完
    @Test
    fun `multiple books and records`() {
        val a = book("A", durChapterIndex = 9, durChapterPos = 5, totalChapterNum = 10) // 已读完
        val b = book("B", durChapterIndex = 2, durChapterPos = 1, totalChapterNum = 10) // 阅读中
        val c = book("C") // 未读
        val stat = buildReadingStatistics(
            books = listOf(a, b, c),
            recordShows = listOf(
                show("A", 100_000L, 3_000L),
                show("B", 50_000L, 2_000L)
            ),
            recordRows = listOf(
                row("", "A", 100_000L, 3_000L),
                row("", "B", 50_000L, 2_000L)
            ),
            totalReadTime = 150_000L
        )
        assertEquals(3, stat.totalBookCount)
        assertEquals(1, stat.finishedCount)
        assertEquals(1, stat.readingCount)
        assertEquals(1, stat.unreadCount)
        assertEquals(150_000L, stat.totalReadTime)
        // items 只包含有阅读记录的书
        assertEquals(listOf("A", "B"), stat.items.map { it.bookName }.sorted())
        // 已读章节估算:A(10) + B(3) + C(0) = 13
        assertEquals(13, stat.estimatedReadChapters)
        assertFalse(stat.hasDuplicateRecords)
    }

    // 4. 同一本书多条历史记录:allShow 已聚合,recordRows 用于识别重复
    @Test
    fun `duplicate rows for the same book are detected and aggregated show is used`() {
        val b = book("A", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val stat = buildReadingStatistics(
            books = listOf(b),
            recordShows = listOf(show("A", 90_000L, 5_000L)), // 数据库聚合后的值
            recordRows = listOf(
                row("androidId", "A", 40_000L, 4_000L),
                row("", "A", 50_000L, 5_000L)
            ),
            totalReadTime = 90_000L
        )
        assertTrue(stat.hasDuplicateRecords)
        assertEquals(1, stat.items.size)
        assertEquals(90_000L, stat.items.single().readTime)
        assertEquals(5_000L, stat.items.single().lastRead)
    }

    // 5. 阅读状态判定复用书架筛选语义(含章节数未知不算已读完)
    @Test
    fun `reading state reuses bookshelf filter semantics`() {
        val unread = book("U", totalChapterNum = 100)
        val reading = book("R", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 100)
        val finished = book("F", durChapterIndex = 99, durChapterPos = 1, totalChapterNum = 100)
        val unknownChapters = book("X", durChapterIndex = 5, durChapterPos = 1, totalChapterNum = 0)
        val stat = buildReadingStatistics(
            books = listOf(unread, reading, finished, unknownChapters),
            recordShows = emptyList(),
            recordRows = emptyList(),
            totalReadTime = 0L
        )
        assertEquals(1, stat.unreadCount)
        assertEquals(2, stat.readingCount)
        assertEquals(1, stat.finishedCount)
    }

    // 6. 阅读时长排行:降序,且排除时长为 0 的书
    @Test
    fun `rank by read time is descending and excludes zero`() {
        val a = book("A", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val b = book("B", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val c = book("C", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val stat = buildReadingStatistics(
            books = listOf(a, b, c),
            recordShows = listOf(
                show("A", 10_000L, 1L),
                show("B", 300_000L, 2L),
                show("C", 0L, 3L)
            ),
            recordRows = emptyList(),
            totalReadTime = 310_000L
        )
        assertEquals(listOf("B", "A"), stat.rankByReadTime.map { it.bookName })
    }

    // 7. 最近阅读:按最后阅读时间降序
    @Test
    fun `recent read is descending by last read time`() {
        val a = book("A", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val b = book("B", durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10)
        val stat = buildReadingStatistics(
            books = listOf(a, b),
            recordShows = listOf(
                show("A", 1_000L, 5_000L),
                show("B", 1_000L, 9_000L)
            ),
            recordRows = emptyList(),
            totalReadTime = 2_000L
        )
        assertEquals(listOf("B", "A"), stat.recentRead.map { it.bookName })
    }

    // 8. 有记录但书已不在书架:仍展示,但标记为不在书架,且不计入书籍总数
    @Test
    fun `record for book not in shelf is kept but marked not in shelf`() {
        val stat = buildReadingStatistics(
            books = emptyList(),
            recordShows = listOf(show("Gone", 7_000L, 8_000L)),
            recordRows = listOf(row("", "Gone", 7_000L, 8_000L)),
            totalReadTime = 7_000L
        )
        val item = stat.items.single()
        assertEquals("Gone", item.bookName)
        assertFalse(item.inBookshelf)
        assertTrue(item.unread)
        assertEquals("", item.author)
        assertEquals(0, item.durChapterIndex)
        assertEquals(7_000L, item.readTime)
        assertEquals(0, stat.totalBookCount)
        assertNull(item.progressPercent)
    }

    // 9. 章节数未知时进度返回 null(宁可不显示,也不伪造)
    @Test
    fun `progress percent is null when chapter count is unknown`() {
        val b = book("A", durChapterIndex = 3, durChapterPos = 1, totalChapterNum = 0)
        val stat = buildReadingStatistics(
            books = listOf(b),
            recordShows = listOf(show("A", 1_000L, 1L)),
            recordRows = emptyList(),
            totalReadTime = 1_000L
        )
        assertNull(stat.items.single().progressPercent)
        assertEquals(4, stat.items.single().readChapterCount)
    }

    // 10. 已读章节被总章节数截断;未读贡献 0
    @Test
    fun `estimated read chapters are clamped and unread contributes zero`() {
        val over = book("O", durChapterIndex = 50, durChapterPos = 1, totalChapterNum = 10)
        val unread = book("U", totalChapterNum = 10)
        val stat = buildReadingStatistics(
            books = listOf(over, unread),
            recordShows = emptyList(),
            recordRows = emptyList(),
            totalReadTime = 0L
        )
        assertEquals(10, stat.estimatedReadChapters)
    }

    // 11. 进度上限 100%(不因异常数据超过 100)
    @Test
    fun `progress percent never exceeds 100`() {
        val over = book("O", durChapterIndex = 50, durChapterPos = 1, totalChapterNum = 10)
        val stat = buildReadingStatistics(
            books = listOf(over),
            recordShows = listOf(show("O", 1L, 1L)),
            recordRows = emptyList(),
            totalReadTime = 1L
        )
        assertEquals(100, stat.items.single().progressPercent)
        assertEquals(10, stat.items.single().readChapterCount)
    }

    // 12. 统计计算为只读:不修改任何入参
    @Test
    fun `statistics computation never mutates input`() {
        val b = book("A", durChapterIndex = 3, durChapterPos = 7, totalChapterNum = 10)
        val books = listOf(b)
        val shows = listOf(show("A", 1_000L, 2_000L))
        val rows = listOf(row("", "A", 1_000L, 2_000L))
        buildReadingStatistics(books, shows, rows, 1_000L)
        assertEquals(3, b.durChapterIndex)
        assertEquals(7, b.durChapterPos)
        assertEquals(10, b.totalChapterNum)
        assertEquals(1_000L, shows.first().readTime)
        assertEquals(2_000L, rows.first().lastRead)
    }

    // 13. 同名不同作者的多本书:与既有 findByName().first() 语义一致,取第一本
    @Test
    fun `same name multiple books resolves to the first one`() {
        val first = book(
            "A", author = "a1",
            durChapterIndex = 1, durChapterPos = 1, totalChapterNum = 10
        )
        val second = book(
            "A", author = "a2",
            durChapterIndex = 9, durChapterPos = 1, totalChapterNum = 10
        )
        val stat = buildReadingStatistics(
            books = listOf(first, second),
            recordShows = listOf(show("A", 1_000L, 1L)),
            recordRows = emptyList(),
            totalReadTime = 1_000L
        )
        assertEquals("a1", stat.items.single().author)
        assertEquals(1, stat.items.single().durChapterIndex)
    }

    // 14. 时长格式化:与抽取前 ReadRecordActivity.formatDuring 行为完全一致
    @Test
    fun `read duration formatting matches legacy behaviour`() {
        assertEquals("0秒", 0L.toReadDuration())
        assertEquals("1秒", 1_000L.toReadDuration())
        assertEquals("1分钟", 60_000L.toReadDuration())
        assertEquals("1小时", 3_600_000L.toReadDuration())
        assertEquals("1天", 86_400_000L.toReadDuration())
        assertEquals("1天1小时1分钟1秒", 90_061_000L.toReadDuration())
        // 不足 1 秒仍归为 0 秒
        assertEquals("0秒", 999L.toReadDuration())
    }
}
