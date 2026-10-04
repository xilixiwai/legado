package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordShow

/**
 * 阅读统计(只读聚合)。
 *
 * 数据来源:现有 [Book] 阅读进度字段 + `readRecord` 表。
 * 本文件**不写入任何数据**,不新增字段/表,也不参与阅读链路。
 *
 * 口径说明(避免制造"看起来很准确的假统计"):
 * - 阅读时长、最后阅读时间 = `readRecord` 的累计值,是**真实记录**;
 * - 阅读中 / 已读完 / 未读 = 直接复用 [filterBooks] 的既有判定,是**真实状态**;
 * - 已读章节 = 按阅读位置 `durChapterIndex` 推算的**估算值**,不是精确计数;
 * - 阅读字数 = 现有字段无法可靠计算,因此**不提供**(不伪造)。
 */
data class BookReadStat(
    val bookName: String,
    val author: String,
    val origin: String,
    val coverUrl: String?,
    val readTime: Long,
    val lastRead: Long,
    val durChapterIndex: Int,
    val totalChapterNum: Int,
    val unread: Boolean,
    val reading: Boolean,
    val finished: Boolean,
    /** 该书是否仍在书架中(记录可能来自已删除的书) */
    val inBookshelf: Boolean
) {

    /** 阅读进度百分比 0..100;章节数未知时返回 null(不伪造) */
    val progressPercent: Int?
        get() {
            if (totalChapterNum <= 0) return null
            if (unread) return 0
            val read = (durChapterIndex + 1).coerceAtMost(totalChapterNum)
            return (read * 100 / totalChapterNum).coerceIn(0, 100)
        }

    /** 已读章节(估算:按阅读位置推算) */
    val readChapterCount: Int
        get() {
            if (unread) return 0
            val count = durChapterIndex.coerceAtLeast(0) + 1
            return if (totalChapterNum > 0) count.coerceAtMost(totalChapterNum) else count
        }
}

data class ReadingStatistics(
    val totalBookCount: Int,
    val unreadCount: Int,
    val readingCount: Int,
    val finishedCount: Int,
    val totalReadTime: Long,
    val estimatedReadChapters: Int,
    /**
     * 是否存在同一本书的多条 readRecord。
     * 历史迁移(deviceId=androidId)与运行时写入(deviceId="")并存时会为 true,
     * 此时聚合的 `sum(readTime)` 可能偏高。
     */
    val hasDuplicateRecords: Boolean,
    val items: List<BookReadStat>
) {

    /** 阅读时长排行(降序,只含有阅读时长的书) */
    val rankByReadTime: List<BookReadStat>
        get() = items.filter { it.readTime > 0 }.sortedByDescending { it.readTime }

    /** 最近阅读(按最后阅读时间降序) */
    val recentRead: List<BookReadStat>
        get() = items.filter { it.lastRead > 0 }.sortedByDescending { it.lastRead }

    companion object {
        /** 排行榜 / 最近阅读最多展示的条数 */
        const val TOP_LIMIT = 10
    }
}

/**
 * 由现有数据构建阅读统计。纯函数,不访问数据库、不修改入参。
 *
 * @param books        书架全部书籍(`BookDao.all`)
 * @param recordShows  按书聚合后的阅读记录(`ReadRecordDao.allShow`)
 * @param recordRows   原始阅读记录(`ReadRecordDao.all`),仅用于识别重复记录
 * @param totalReadTime 总阅读时长(`ReadRecordDao.allTime`)
 */
fun buildReadingStatistics(
    books: List<Book>,
    recordShows: List<ReadRecordShow>,
    recordRows: List<ReadRecord>,
    totalReadTime: Long
): ReadingStatistics {
    // 复用既有书架筛选判定,不重复实现"未读/阅读中/已读完"逻辑
    val unreadSet = filterBooks(books, BookshelfFilter.UNREAD).toHashSet()
    val readingSet = filterBooks(books, BookshelfFilter.READING).toHashSet()
    val finishedSet = filterBooks(books, BookshelfFilter.FINISHED).toHashSet()

    // 书名 -> 书籍(同名多书时取第一本,与既有 ReadRecordActivity 的 findByName 语义一致)
    val bookByName = HashMap<String, Book>(books.size)
    books.forEach { book -> bookByName.putIfAbsent(book.name, book) }

    val items = recordShows.map { show ->
        val book = bookByName[show.bookName]
        BookReadStat(
            bookName = show.bookName,
            author = book?.author ?: "",
            origin = book?.origin ?: "",
            coverUrl = book?.getDisplayCover(),
            readTime = show.readTime,
            lastRead = show.lastRead,
            durChapterIndex = book?.durChapterIndex ?: 0,
            totalChapterNum = book?.totalChapterNum ?: 0,
            unread = book == null || book in unreadSet,
            reading = book != null && book in readingSet,
            finished = book != null && book in finishedSet,
            inBookshelf = book != null
        )
    }

    val hasDuplicateRecords = recordRows
        .groupingBy { it.bookName }
        .eachCount()
        .any { it.value > 1 }

    return ReadingStatistics(
        totalBookCount = books.size,
        unreadCount = unreadSet.size,
        readingCount = readingSet.size,
        finishedCount = finishedSet.size,
        totalReadTime = totalReadTime,
        estimatedReadChapters = books.sumOf { book ->
            if (book in unreadSet) {
                0
            } else {
                val count = book.durChapterIndex.coerceAtLeast(0) + 1
                if (book.totalChapterNum > 0) count.coerceAtMost(book.totalChapterNum) else count
            }
        },
        hasDuplicateRecords = hasDuplicateRecords,
        items = items
    )
}
