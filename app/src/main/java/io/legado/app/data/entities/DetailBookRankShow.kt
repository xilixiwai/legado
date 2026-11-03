package io.legado.app.data.entities

import io.legado.app.constant.BookType

/**
 * 每日明细按周期排行的查询投影（SQL group by bookName, bookAuthor 的结果行）。
 */
data class DetailBookRankShow(
    val bookName: String,
    val bookAuthor: String,
    /** 组内 bookType 快照的最大值，仅用于小说/漫画筛选（image 位） */
    val bookType: Int,
    val readTime: Long,
    val lastReadTime: Long
) {

    val isManga: Boolean
        get() = bookType and BookType.image != 0
}
