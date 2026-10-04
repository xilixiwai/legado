package io.legado.app.help.book

import io.legado.app.R
import io.legado.app.data.entities.Book

/**
 * 书架高级筛选。
 *
 * 筛选是纯粹的查询/展示条件:只读取 Book 的既有字段,不修改任何数据、
 * 不触发网络请求,也不依赖数据库结构变更。
 */
enum class BookshelfFilter(val titleRes: Int) {
    ALL(R.string.filter_all),
    UNREAD(R.string.filter_unread),
    READING(R.string.filter_reading),
    FINISHED(R.string.filter_finished),
    RECENT_READ(R.string.filter_recent_read),
    RECENT_UPDATED(R.string.filter_recent_updated);

    companion object {
        fun fromOrdinal(ordinal: Int): BookshelfFilter =
            entries.getOrElse(ordinal) { ALL }
    }
}

/** “最近阅读/最近更新”的时间范围:7 天 */
const val BOOKSHELF_RECENT_RANGE = 7 * 24 * 3600 * 1000L

/** 该书是否已开始阅读(数据来源:saveRead 持久化的进度字段,只读判断) */
private fun Book.hasRead(): Boolean = durChapterIndex > 0 || durChapterPos > 0

/** 该书是否读到最后一章(totalChapterNum 未知时不判定为已读完) */
private fun Book.isFinished(): Boolean =
    totalChapterNum > 0 && durChapterIndex >= totalChapterNum - 1

/**
 * 按筛选条件过滤书架书籍。
 * @param now 当前时间戳,由调用方注入以便测试
 */
fun filterBooks(
    books: List<Book>,
    filter: BookshelfFilter,
    now: Long = System.currentTimeMillis()
): List<Book> = when (filter) {
    BookshelfFilter.ALL -> books
    BookshelfFilter.UNREAD -> books.filterNot { it.hasRead() }
    BookshelfFilter.READING -> books.filter { it.hasRead() && !it.isFinished() }
    BookshelfFilter.FINISHED -> books.filter { it.isFinished() }
    BookshelfFilter.RECENT_READ -> books.filter {
        it.hasRead() && it.durChapterTime >= now - BOOKSHELF_RECENT_RANGE
    }

    BookshelfFilter.RECENT_UPDATED -> books.filter {
        it.latestChapterTime >= now - BOOKSHELF_RECENT_RANGE
    }
}
