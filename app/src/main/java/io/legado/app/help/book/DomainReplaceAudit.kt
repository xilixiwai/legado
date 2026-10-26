package io.legado.app.help.book

/**
 * 「批量替换域名」执行结果的审计口径。
 *
 * [DomainReplacePlannedCounts] 是执行阶段用与预览**完全相同**的纯函数算出的「计划应更新数量」；
 * [DomainReplaceCounts] 是各条 UPDATE 返回的真实影响行数。两条口径互相独立，
 * 任何不一致都说明匹配与写入两侧的语义出现了漂移，必须显式报告而不是当成成功展示。
 */
data class DomainReplacePlannedCounts(
    /** bookUrl 或 tocUrl 有变化的书籍数（对应 UPDATE_BOOK_URLS 影响行数的口径） */
    val books: Int = 0,
    val chapterBookUrl: Int = 0,
    val chapterUrl: Int = 0,
    val chapterBaseUrl: Int = 0,
    val chapterResourceUrl: Int = 0,
) {
    val total: Int
        get() = books + chapterBookUrl + chapterUrl + chapterBaseUrl + chapterResourceUrl
}

/**
 * 计划数与实际影响行数的对照。
 *
 * 预览有命中但实际更新为零（计划与实际不一致的极端情形）会被本对照检出，
 * 结果界面据此明确报告「替换可能未完成」，绝不把这种情况当成功展示。
 */
object DomainReplaceAudit {

    /** 一个字段的计划数与实际数 */
    data class FieldCount(
        val field: String,
        val planned: Int,
        val actual: Int,
    )

    /**
     * 对照计划数与实际影响行数，返回不一致的字段（完全一致返回空列表）。
     *
     * `chapters.url` 的实际口径 = 改写行数 + 因撞键被删除的重复行数：
     * 撞键行删除后不再被改写，删除与改写合计才等于计划命中数。
     * [planned] 为 null 表示没有可对照的计划（输入非法等提前终止），不视为不一致。
     */
    fun compare(
        planned: DomainReplacePlannedCounts?,
        counts: DomainReplaceCounts,
    ): List<FieldCount> {
        if (planned == null) return emptyList()
        return listOf(
            FieldCount("books", planned.books, counts.books),
            FieldCount("chapters.bookUrl", planned.chapterBookUrl, counts.chapterBookUrl),
            FieldCount(
                "chapters.url",
                planned.chapterUrl,
                counts.chapterUrl + counts.chapterUrlDuplicates
            ),
            FieldCount("chapters.baseUrl", planned.chapterBaseUrl, counts.chapterBaseUrl),
            FieldCount(
                "chapters.resourceUrl",
                planned.chapterResourceUrl,
                counts.chapterResourceUrl
            ),
        ).filter { it.planned != it.actual }
    }
}
