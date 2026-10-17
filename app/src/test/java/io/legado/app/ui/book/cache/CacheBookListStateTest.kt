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
 * 默认顺序 / 数字升序 / 相同数量稳定性 / 本地书籍末尾 / 恢复默认 / 反复切换 / 刷新后维护。
 */
class CacheBookListStateTest {

    private fun book(name: String) = Book(bookUrl = name, name = name, author = "a")

    private fun localBook(name: String) = Book(
        bookUrl = name,
        name = name,
        author = "a",
        type = BookType.text or BookType.local,
        origin = BookType.localTag,
    )

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

    @Test
    fun `local detection uses book type instead of cache statistics`() {
        // 与列表展示一致,本地书籍由 Book.isLocal(type/origin) 判定,不是按是否有缓存统计判定
        assertTrue(localBook("l1").isLocal)
        assertFalse(book("n1").isLocal)
    }

    @Test
    fun `network book without cache entry still sorts before local books`() {
        val state = state(listOf(localBook("l1"), book("n1")))
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        assertEquals(listOf("n1", "l1"), state.items.map { it.bookUrl })
    }

    @Test
    fun `local books go last and keep their relative order`() {
        val network1 = book("n1")
        val local1 = localBook("l1")
        val network2 = book("n2")
        val local2 = localBook("l2")
        val network3 = book("n3")
        val counts = mutableMapOf("n1" to 0, "n2" to 10, "n3" to 2)
        val books = listOf(network1, local1, network2, local2, network3)
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        // 网络书籍按 0、2、10 升序;本地书籍 l1、l2 按默认顺序排在末尾
        assertEquals(
            listOf("n1", "n3", "n2", "l1", "l2"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `local books do not participate even when count provider has a value`() {
        val local1 = localBook("l1")
        val network1 = book("n1")
        val local2 = localBook("l2")
        val network2 = book("n2")
        val counts = mutableMapOf("l1" to 1, "n1" to 5, "l2" to 2, "n2" to 3)
        val state = state(listOf(local1, network1, local2, network2), counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        // 本地书籍即使能取到数字也不参与排序,仍按原相对顺序排在网络书籍之后
        assertEquals(
            listOf("n2", "n1", "l1", "l2"),
            state.items.map { it.bookUrl }
        )
    }

    @Test
    fun `restore default returns full list including local books`() {
        val books = listOf(
            localBook("l1"),
            book("n1"),
            localBook("l2"),
            book("n2")
        )
        val state = state(books, mutableMapOf("n1" to 9, "n2" to 1))
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)
        assertEquals(listOf("n2", "n1", "l1", "l2"), state.items.map { it.bookUrl })

        state.setSortMode(CacheBookListState.SortMode.DEFAULT)

        assertEquals(
            listOf("l1", "n1", "l2", "n2"),
            state.items.map { it.bookUrl }
        )
        assertSame(books, state.items)
    }

    @Test
    fun `equal network counts stay stable when local books are interleaved`() {
        val books = listOf(
            book("n1"),
            localBook("l1"),
            book("n2"),
            book("n3"),
            localBook("l2")
        )
        val counts = mutableMapOf("n1" to 2, "n2" to 0, "n3" to 2)
        val state = state(books, counts)
        state.setSortMode(CacheBookListState.SortMode.DOWNLOADED_ASC)

        assertEquals(
            listOf("n2", "n1", "n3", "l1", "l2"),
            state.items.map { it.bookUrl }
        )
    }
}
