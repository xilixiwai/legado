package io.legado.app.ui.book.cache

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 缓存/导出页排序状态回归测试(纯逻辑,不依赖 Android):
 * 默认顺序 / 数字升序 / 相同数量稳定性 / 恢复默认 / 反复切换 / 刷新后维护。
 */
class CacheBookListStateTest {

    private fun book(name: String) = Book(bookUrl = name, name = name, author = "a")

    private fun state(
        books: List<Book>,
        counts: MutableMap<String, Int> = mutableMapOf()
    ) = CacheBookListState { counts[it.bookUrl] ?: 0 }.apply {
        updateSource(books)
    }

    @Test
    fun `default mode keeps source order`() {
        val books = listOf(book("c"), book("a"), book("b"))
        val state = state(books)

        assertSame(books, state.items)
    }

    @Test
    fun `downloaded asc compares numbers instead of strings`() {
        val counts = mutableMapOf("ten" to 10, "two" to 2, "twenty" to 20, "zero" to 0)
        val books = listOf(book("ten"), book("two"), book("twenty"), book("zero"))
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        // 字符串比较会得到 10 < 2 < 20 的错误顺序,数字比较才是 0 < 2 < 10 < 20
        assertEquals(listOf("zero", "two", "ten", "twenty"), state.items.map { it.bookUrl })
    }

    @Test
    fun `equal counts keep original relative order`() {
        val counts = mutableMapOf("a" to 2, "b" to 0, "c" to 2, "d" to 0, "e" to 1)
        val books = listOf(book("a"), book("b"), book("c"), book("d"), book("e"))
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        assertEquals(listOf("b", "d", "e", "a", "c"), state.items.map { it.bookUrl })
    }

    @Test
    fun `repeated asc and default toggles stay correct`() {
        val counts = mutableMapOf("a" to 3, "b" to 0, "c" to 1)
        val books = listOf(book("a"), book("b"), book("c"))
        val state = state(books, counts)

        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)
        val first = state.items.map { it.bookUrl }
        state.setSortMode(CacheBookListState.SortMode.DEFAULT)
        val restored = state.items.map { it.bookUrl }
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)
        val second = state.items.map { it.bookUrl }

        assertEquals(listOf("b", "c", "a"), first)
        assertEquals(listOf("a", "b", "c"), restored)
        assertEquals(first, second)
    }

    @Test
    fun `updated source is used as the new default while sorting stays active`() {
        val counts = mutableMapOf("a" to 5, "b" to 1, "c" to 3)
        val books = listOf(book("a"), book("b"))
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        val refreshed = listOf(book("c"), book("b"), book("a"))
        state.updateSource(refreshed)

        assertEquals(listOf("b", "c", "a"), state.items.map { it.bookUrl })

        state.setSortMode(CacheBookListState.SortMode.DEFAULT)

        assertEquals(listOf("c", "b", "a"), state.items.map { it.bookUrl })
        assertSame(refreshed, state.items)
    }

    @Test
    fun `counts are read live so late cache scan results reorder the list`() {
        val counts = mutableMapOf<String, Int>()
        val books = listOf(book("a"), book("b"), book("c"))
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        assertEquals(listOf("a", "b", "c"), state.items.map { it.bookUrl })

        counts["a"] = 10
        counts["b"] = 2
        counts["c"] = 0

        assertEquals(listOf("c", "b", "a"), state.items.map { it.bookUrl })
    }

    @Test
    fun `sorting never drops or duplicates books`() {
        val counts = mutableMapOf("a" to 0, "b" to 0, "c" to 0, "d" to 0)
        val books = listOf(book("a"), book("b"), book("c"), book("d"))
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        assertEquals(books.size, state.items.size)
        assertEquals(books.map { it.bookUrl }.toSet(), state.items.map { it.bookUrl }.toSet())
    }
}
