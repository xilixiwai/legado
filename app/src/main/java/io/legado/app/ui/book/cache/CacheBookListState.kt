package io.legado.app.ui.book.cache

import io.legado.app.data.entities.Book

/**
 * 缓存/导出页的列表展示状态。
 *
 * 只负责展示顺序,不修改 [Book] 数据,也不持久化排序结果。
 * [updateSource] 保存的始终是页面默认顺序;升序排序只在它的副本上进行,
 * 因此可以随时恢复默认顺序,不会把排序后的结果当成新的默认顺序。
 */
class CacheBookListState(
    private val downloadedCount: (Book) -> Int,
) {

    var sortMode: SortMode = SortMode.DEFAULT
        private set

    private var source: List<Book> = emptyList()

    /**
     * 当前应展示的列表。
     *
     * 数量由 [downloadedCount] 实时读取,所以缓存扫描完成后能按真实数量重排。
     */
    val items: List<Book>
        get() = when (sortMode) {
            SortMode.DEFAULT -> source
            SortMode.DOWNLOADED_ASC -> sortByDownloadedAsc(source)
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

    private fun sortByDownloadedAsc(books: List<Book>): List<Book> {
        // 数量相同时用原索引兜底,保证排序稳定,不会无意义地打乱相对顺序。
        return books.withIndex()
            .sortedWith(compareBy({ downloadedCount(it.value) }, { it.index }))
            .map { it.value }
    }

    enum class SortMode {
        DEFAULT,
        DOWNLOADED_ASC
    }
}
