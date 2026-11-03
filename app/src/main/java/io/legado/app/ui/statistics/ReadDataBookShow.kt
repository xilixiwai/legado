package io.legado.app.ui.statistics

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 阅读数据中心「排行 / 最近阅读」列表行（总览与排行 Tab 共用）。
 */
data class ReadDataBookShow(
    val bookName: String,
    val bookAuthor: String,
    val readTime: Long,
    val lastRead: Long,
    val bookType: Int,
    val coverUrl: String? = null,
    val origin: String? = null,
    /** 已删除书籍(书架无此书)时不展示进度等信息 */
    val inBookshelf: Boolean = false
) {
    val isManga: Boolean
        get() = bookType and io.legado.app.constant.BookType.image != 0

    companion object {

        /** 由每日明细排行行构造（异步补封面/书架状态） */
        suspend fun fromRank(
            bookName: String,
            bookAuthor: String,
            readTime: Long,
            lastReadTime: Long,
            bookType: Int
        ): ReadDataBookShow = withContext(Dispatchers.IO) {
            val book: Book? = appDb.bookDao.getByNameAuthor(bookName, bookAuthor)
                ?: appDb.bookDao.findByName(bookName).firstOrNull()
            ReadDataBookShow(
                bookName = bookName,
                bookAuthor = bookAuthor,
                readTime = readTime,
                lastRead = lastReadTime,
                bookType = bookType,
                coverUrl = book?.getDisplayCover(),
                origin = book?.origin,
                inBookshelf = book != null
            )
        }
    }
}
