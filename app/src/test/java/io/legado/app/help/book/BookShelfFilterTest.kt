package io.legado.app.help.book

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架高级筛选回归测试(纯函数,时间由 now 注入)。
 * 覆盖:无筛选一致性 / 单条件 / 多条件正交 / 空结果 / 分组正交 / 阅读状态 /
 * 条件切换 / 清除恢复 / 阅读进度不可变
 */
class BookShelfFilterTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 3600 * 1000L

    private fun unreadBook(
        name: String = "unread",
        group: Long = 0,
        latestChapterTime: Long = now - 10 * day
    ) = Book(name = name, author = "a", group = group).apply {
        durChapterIndex = 0
        durChapterPos = 0
        durChapterTime = now - 2 * day // 新加书 durChapterTime 默认接近 now,但未读
        this.latestChapterTime = latestChapterTime
        totalChapterNum = 100
    }

    private fun readingBook(
        name: String = "reading",
        group: Long = 0,
        lastRead: Long = now - day,
        latestChapterTime: Long = now - 10 * day,
        durChapterIndex: Int = 5
    ) = Book(name = name, author = "a", group = group).apply {
        this.durChapterIndex = durChapterIndex
        durChapterPos = 100
        durChapterTime = lastRead
        this.latestChapterTime = latestChapterTime
        totalChapterNum = 100
    }

    private fun finishedBook(
        name: String = "finished",
        group: Long = 0,
        lastRead: Long = now - day
    ) = Book(name = name, author = "a", group = group).apply {
        durChapterIndex = 99 // totalChapterNum - 1
        durChapterPos = 100
        durChapterTime = lastRead
        latestChapterTime = now - 10 * day
        totalChapterNum = 100
    }

    // 1. 无筛选 = 与现有书架完全一致
    @Test
    fun `no filter returns the exact same list`() {
        val books = listOf(unreadBook(), readingBook(), finishedBook())
        assertSame(books, filterBooks(books, BookshelfFilter.ALL, now))
        assertEquals(books, filterBooks(books, BookshelfFilter.ALL, now))
    }

    // 2. 单条件筛选:未读
    @Test
    fun `unread filter keeps only books never opened`() {
        val books = listOf(unreadBook("a"), readingBook("b"), finishedBook("c"))
        val result = filterBooks(books, BookshelfFilter.UNREAD, now)
        assertEquals(listOf("a"), result.map { it.name })
    }

    // 3. 阅读状态筛选:阅读中 / 已读完(含边界:最后一章开头即视为已读完)
    @Test
    fun `reading filter excludes unread and finished books`() {
        val books = listOf(unreadBook("a"), readingBook("b"), finishedBook("c"))
        val result = filterBooks(books, BookshelfFilter.READING, now)
        assertEquals(listOf("b"), result.map { it.name })
    }

    @Test
    fun `finished filter keeps books at last chapter only`() {
        val border = readingBook("border", durChapterIndex = 99)
        val books = listOf(unreadBook("a"), readingBook("b", durChapterIndex = 5), border)
        val result = filterBooks(books, BookshelfFilter.FINISHED, now)
        assertEquals(listOf("border"), result.map { it.name })
    }

    @Test
    fun `book with unknown chapter count is never finished`() {
        val book = readingBook("unknown")
        book.totalChapterNum = 0
        assertTrue(filterBooks(listOf(book), BookshelfFilter.FINISHED, now).isEmpty())
        assertTrue(filterBooks(listOf(book), BookshelfFilter.READING, now).isNotEmpty())
    }

    // 4. 空结果
    @Test
    fun `filter can produce an empty result`() {
        val books = listOf(unreadBook(), unreadBook())
        assertTrue(filterBooks(books, BookshelfFilter.READING, now).isEmpty())
        assertTrue(filterBooks(emptyList(), BookshelfFilter.ALL, now).isEmpty())
    }

    // 5. 分组筛选正交:同一筛选谓词适用于任意分组,不读取、不修改 group 字段
    @Test
    fun `filter is orthogonal to group mechanism`() {
        val books = listOf(
            unreadBook("a", group = 1),
            readingBook("b", group = 2),
            finishedBook("c", group = 3)
        )
        val result = filterBooks(books, BookshelfFilter.UNREAD, now)
        assertEquals(listOf("a"), result.map { it.name })
        assertTrue(result.all { it.group != 0L || it.name == "a" })
    }

    // 6. 最近阅读:必须已读过且 7 天内有阅读记录
    @Test
    fun `recent read requires real progress within range`() {
        val books = listOf(
            readingBook("fresh", lastRead = now - day),
            readingBook("stale", lastRead = now - 8 * day),
            unreadBook("never") // durChapterTime 虽接近 now,但从未阅读
        )
        val result = filterBooks(books, BookshelfFilter.RECENT_READ, now)
        assertEquals(listOf("fresh"), result.map { it.name })
    }

    // 7. 最近更新
    @Test
    fun `recent updated follows latestChapterTime`() {
        val books = listOf(
            readingBook("updated", latestChapterTime = now - day),
            readingBook("old", latestChapterTime = now - 8 * day)
        )
        val result = filterBooks(books, BookshelfFilter.RECENT_UPDATED, now)
        assertEquals(listOf("updated"), result.map { it.name })
    }

    // 8. 切换筛选条件 / 9. 清除筛选恢复全部
    @Test
    fun `switching filters changes results and all restores everything`() {
        val books = listOf(unreadBook(), readingBook(), finishedBook())
        val byUnread = filterBooks(books, BookshelfFilter.UNREAD, now)
        val byReading = filterBooks(books, BookshelfFilter.READING, now)
        assertTrue(byUnread.map { it.name } != byReading.map { it.name })
        assertEquals(books, filterBooks(books, BookshelfFilter.fromOrdinal(0), now))
        assertEquals(BookshelfFilter.ALL, BookshelfFilter.fromOrdinal(999))
    }

    // 10. 筛选不修改数据:阅读进度字段在筛选前后完全一致
    @Test
    fun `filtering never mutates book data`() {
        val book = readingBook().apply {
            durChapterIndex = 42
            durChapterPos = 12345
            durChapterTime = now - day
            latestChapterTime = now - 2 * day
        }
        val snapshot = Book(book.name, book.author).apply {
            durChapterIndex = 42
            durChapterPos = 12345
            durChapterTime = book.durChapterTime
            latestChapterTime = book.latestChapterTime
            totalChapterNum = book.totalChapterNum
        }
        BookshelfFilter.entries.forEach { filter ->
            filterBooks(listOf(book), filter, now)
            assertEquals(snapshot.durChapterIndex, book.durChapterIndex)
            assertEquals(snapshot.durChapterPos, book.durChapterPos)
            assertEquals(snapshot.durChapterTime, book.durChapterTime)
            assertEquals(snapshot.latestChapterTime, book.latestChapterTime)
            assertEquals(snapshot.totalChapterNum, book.totalChapterNum)
            // 通过筛选的书必须是原实例本身(不得克隆/替换对象)
            filterBooks(listOf(book), filter, now).forEach { filtered ->
                assertSame(book, filtered)
            }
        }
    }

    // 边界:1 章 + 未读 ≠ 已读完(未读状态优先,对齐成熟实现的判定顺序)
    @Test
    fun `one chapter unread book is not finished`() {
        val book = Book(name = "one", author = "a").apply {
            totalChapterNum = 1
            durChapterIndex = 0
            durChapterPos = 0
            durChapterTime = now - 2 * day
            latestChapterTime = now - 10 * day
        }
        assertEquals(listOf(book), filterBooks(listOf(book), BookshelfFilter.UNREAD, now))
        assertTrue(filterBooks(listOf(book), BookshelfFilter.FINISHED, now).isEmpty())
        assertTrue(filterBooks(listOf(book), BookshelfFilter.READING, now).isEmpty())
    }

    // 边界:1 章 + 已开始阅读 = 已读完
    @Test
    fun `one chapter started book is finished`() {
        val book = Book(name = "one", author = "a").apply {
            totalChapterNum = 1
            durChapterIndex = 0
            durChapterPos = 1
            durChapterTime = now - day
            latestChapterTime = now - 10 * day
        }
        assertEquals(listOf(book), filterBooks(listOf(book), BookshelfFilter.FINISHED, now))
        assertTrue(filterBooks(listOf(book), BookshelfFilter.UNREAD, now).isEmpty())
        assertTrue(filterBooks(listOf(book), BookshelfFilter.READING, now).isEmpty())
    }

    // 边界:翻到最后一章(index>0 视为已开始阅读)但页内进度为 0 = 已读完
    @Test
    fun `last chapter with zero position and positive index is finished`() {
        val book = Book(name = "last", author = "a").apply {
            totalChapterNum = 100
            durChapterIndex = 99
            durChapterPos = 0
            durChapterTime = now - day
            latestChapterTime = now - 10 * day
        }
        assertEquals(listOf(book), filterBooks(listOf(book), BookshelfFilter.FINISHED, now))
        assertTrue(filterBooks(listOf(book), BookshelfFilter.UNREAD, now).isEmpty())
    }

    // 边界:多章书第一章已开始阅读 = 阅读中,不是已读完也不是未读
    @Test
    fun `first chapter started multi chapter book is reading`() {
        val book = Book(name = "mid", author = "a").apply {
            totalChapterNum = 100
            durChapterIndex = 0
            durChapterPos = 50
            durChapterTime = now - day
            latestChapterTime = now - 10 * day
        }
        assertEquals(listOf(book), filterBooks(listOf(book), BookshelfFilter.READING, now))
        assertTrue(filterBooks(listOf(book), BookshelfFilter.FINISHED, now).isEmpty())
        assertTrue(filterBooks(listOf(book), BookshelfFilter.UNREAD, now).isEmpty())
    }
}
