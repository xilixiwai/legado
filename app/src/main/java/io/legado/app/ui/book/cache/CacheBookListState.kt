package io.legado.app.ui.book.cache

import io.legado.app.data.entities.Book
import io.legado.app.help.book.isLocal

/**
 * 缓存/导出页的列表展示状态。
 *
 * 只负责展示顺序,不修改 [Book] 数据,也不持久化排序结果。
 * [updateSource] 保存的始终是页面默认顺序;排序只在它的副本上进行,
 * 因此可以随时恢复默认顺序,不会把排序后的结果当成新的默认顺序。
 */
class CacheBookListState(
    private val cachedChapterCount: (Book) -> Int,
) {

    var sortMode: SortMode = SortMode.DEFAULT
        private set

    private var source: List<Book> = emptyList()

    /**
     * 当前应展示的列表。
     *
     * 完成状态由 [cachedChapterCount] 和章节总数实时判断,
     * 所以缓存扫描完成后能按最新状态重新分组。
     */
    val items: List<Book>
        get() = when (sortMode) {
            SortMode.DEFAULT -> source
            SortMode.INCOMPLETE_FIRST -> sortIncompleteFirst(source)
        }

    /**
     * 数据刷新(加载、增删、分组切换)时更新默认顺序,不改变已选排序模式。
     */
    fun updateSource(books: List<Book>) {
        source = books
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
    }

    /**
     * 未下载完成的网络书籍排在前面,已完成的随后,本地书籍固定在末尾。
     * 分组内保持默认顺序中的相对顺序,不再按缓存章节数量二次排序。
     */
    private fun sortIncompleteFirst(books: List<Book>): List<Book> {
        val (networkBooks, localBooks) = books.partition { !it.isLocal }
        val (incompleteBooks, completedBooks) =
            networkBooks.partition { !isDownloadCompleted(it) }
        return incompleteBooks + completedBooks + localBooks
    }

    /**
     * 只有章节总数已知且缓存的章节数达到总数时才视为已下载完成。
     * 总数未知(<=0)或缓存统计尚未就绪时一律视为未完成,避免漏掉待预下载的书籍。
     */
    private fun isDownloadCompleted(book: Book): Boolean {
        val total = book.totalChapterNum
        if (total <= 0) return false
        return cachedChapterCount(book) >= total
    }

    enum class SortMode {
        DEFAULT,
        INCOMPLETE_FIRST
    }
}
