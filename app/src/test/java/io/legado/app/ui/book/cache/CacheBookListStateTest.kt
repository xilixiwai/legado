package io.legado.app.ui.book.cache

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存/导出页排序状态回归测试(纯逻辑,不依赖 Android):
 * 未下载优先 / 部分下载 / 已完成 / 单章节 / 本地书籍末尾 / 未知总数 / 恢复默认 / 实时状态。
 */
class CacheBookListStateTest {

    private fun book(name: String, totalChapterNum: Int = 100) =
        Book(bookUrl = name, name = name, author = "a").apply {
            this.totalChapterNum = totalChapterNum
        }

    private fun localBook(name: String, totalChapterNum: Int = 100) = Book(
        bookUrl = name,
        name = name,
        author = "a",
        type = BookType.text or BookType.local,
        origin = BookType.localTag,
    ).apply {
        this.totalChapterNum = totalChapterNum
    }

    private fun state(
        books: List<Book>,
        cachedCounts: MutableMap<String, Int> = mutableMapOf()
    ) = CacheBookListState { cachedCounts[it.bookUrl] ?: 0 }.apply {
        updateSource(books)
    }

    @Test
    fun `default mode keeps source order`() {
        val books = listOf(book("c"), book("a"), book("b"))
        val state = state(books)

        assertSame(books, state.items)
    }

    @Test
    fun `unfinished books are shown before completed ones`() {
        val counts = mutableMapOf("complete" to 100, "notStarted" to 0, "partial" to 100, "single" to 1)
        val complete = book("complete", 100)
        val notStarted = book("notStarted", 100)
        val single = book("single", 1)
        val partial = book("partial", 500)
        val state = state(listOf(complete, notStarted, single, partial), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        // 未开始、部分下载在前(保持默认相对顺序),已完成的 100/100 和 1/1 在后
        assertEquals(
            listOf("notStarted", "partial", "complete", "single"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `completion is not based on downloaded count`() {
        val counts = mutableMapOf("smallComplete" to 10, "largePartial" to 999)
        val smallComplete = book("smallComplete", 10)
        val largePartial = book("largePartial", 1000)
        val state = state(listOf(smallComplete, largePartial), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        // 如果按下载数升序,10 会排在 999 前;按完成状态则应让未完成的 999/1000 优先
        assertEquals(
            listOf("largePartial", "smallComplete"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `unknown total chapter count is treated as unfinished`() {
        val counts = mutableMapOf("unknown1" to 0, "unknown2" to 5, "complete" to 10)
        val unknown1 = book("unknown1", 0)
        val unknown2 = book("unknown2", 0)
        val complete = book("complete", 10)
        val state = state(listOf(complete, unknown1, unknown2), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        assertEquals(
            listOf("unknown1", "unknown2", "complete"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `missing cache statistics is treated as unfinished`() {
        val counts = mutableMapOf("complete" to 100)
        val complete = book("complete", 100)
        val notScanned = book("notScanned", 100)
        val state = state(listOf(complete, notScanned), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        // 缓存扫描尚未完成时按 0 处理,必须留在未完成分组
        assertEquals(
            listOf("notScanned", "complete"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `local books stay last and keep their relative order`() {
        val counts = mutableMapOf("n1" to 100, "n2" to 10)
        val local1 = localBook("l1")
        val network1 = book("n1", 100)
        val local2 = localBook("l2")
        val network2 = book("n2", 100)
        val state = state(listOf(local1, network1, local2, network2), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        assertEquals(
            listOf("n2", "n1", "l1", "l2"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `local books do not participate even when completion data exists`() {
        val counts = mutableMapOf("local" to 100, "network" to 0)
        val local = localBook("local", 100)
        val network = book("network", 100)
        val state = state(listOf(local, network), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        // 本地书籍即使看起来已完成,也不参与完成状态排序,仍在网络书籍之后
        assertEquals(
            listOf("network", "local"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `relative order is stable inside incomplete and completed groups`() {
        val counts = mutableMapOf(
            "c1" to 100, "i1" to 0, "c2" to 100, "i2" to 50, "i3" to 0
        )
        val c1 = book("c1", 100)
        val i1 = book("i1", 100)
        val c2 = book("c2", 100)
        val i2 = book("i2", 100)
        val i3 = book("i3", 100)
        val state = state(listOf(c1, i1, c2, i2, i3), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        assertEquals(
            listOf("i1", "i2", "i3", "c1", "c2"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `restore default returns full list including local books`() {
        val counts = mutableMapOf("n1" to 100, "n2" to 10)
        val books = listOf(
            localBook("l1"),
            book("n1", 100),
            localBook("l2"),
            book("n2", 100)
        )
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)
        assertEquals(listOf("n2", "n1", "l1", "l2"), state.items.map { it.bookUrl })

        state.setSortMode(CacheBookListState.SortMode.DEFAULT)

        assertEquals(
            listOf("l1", "n1", "l2", "n2"),
            state.items.map { it.bookUrl }
        )
        assertSame(books, state.items)
    }

    @Test
    fun `repeated incomplete first and default toggles stay correct`() {
        val counts = mutableMapOf("c1" to 100, "i1" to 0, "c2" to 100, "i2" to 10)
        val books = listOf(book("c1"), book("i1"), book("c2"), book("i2"))
        val state = state(books, counts)

        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)
        val first = state.items.map { it.bookUrl }
        state.setSortMode(CacheBookListState.SortMode.DEFAULT)
        val restored = state.items.map { it.bookUrl }
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)
        val second = state.items.map { it.bookUrl }

        assertEquals(listOf("i1", "i2", "c1", "c2"), first)
        assertEquals(listOf("c1", "i1", "c2", "i2"), restored)
        assertEquals(first, second)
    }

    @Test
    fun `completion status is read live while sorting`() {
        val counts = mutableMapOf("n1" to 100, "n2" to 50)
        val n1 = book("n1", 100)
        val n2 = book("n2", 100)
        val state = state(listOf(n1, n2), counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        assertEquals(listOf("n2", "n1"), state.items.map { it.bookUrl })

        counts["n1"] = 99
        assertEquals(listOf("n1", "n2"), state.items.map { it.bookUrl })

        counts["n2"] = 100
        assertEquals(listOf("n1", "n2"), state.items.map { it.bookUrl })
    }

    @Test
    fun `sorting never drops or duplicates books`() {
        val counts = mutableMapOf("a" to 0, "b" to 100, "c" to 50, "d" to 0)
        val books = listOf(
            book("a"), localBook("b"), book("c"), book("d")
        )
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.INCOMPLETE_FIRST)

        assertEquals(books.size, state.items.size)
        assertEquals(books.map { it.bookUrl }.toSet(), state.items.map { it.bookUrl }.toSet())
    }

    @Test
    fun `local detection uses book type instead of cache statistics`() {
        // 与列表展示一致,本地书籍由 Book.isLocal(type/origin) 判定,不是按是否有缓存统计判定
        assertTrue(localBook("l1").isLocal)
        assertFalse(book("n1").isLocal)
    }
}
