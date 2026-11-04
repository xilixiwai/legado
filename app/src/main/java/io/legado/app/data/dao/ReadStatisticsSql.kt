package io.legado.app.data.dao

/** DAO 与真实 SQLite JVM 回归共用的聚合 SQL；保留原有跨设备汇总口径。 */
object ReadStatisticsSql {
    const val TOTAL_SUM = "select sum(readTime) from readRecordDetail"
    const val DISTINCT_DATE_COUNT = "select count(distinct date) from readRecordDetail"
    const val SUM_RANGE =
        "select sum(readTime) from readRecordDetail where date >= :start and date < :end"
    const val RANK_RANGE =
        """select bookName, bookAuthor, max(bookType) as bookType, sum(readTime) as readTime,
            max(lastReadTime) as lastReadTime
            from readRecordDetail
            where date >= :start and date < :end
            group by bookName, bookAuthor
            order by readTime desc"""
}
