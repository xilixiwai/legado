package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * 每日阅读统计明细（按 设备 × 书籍 × 自然日 一行）。
 *
 * 身份口径：
 * - 书籍身份 = (bookName, bookAuthor)：换源（bookUrl 变更）不影响归属，同名不同作者不互相污染；
 *   bookUrl 是副本身份（换源会删除旧副本并新建），不能作为统计主键。
 * - deviceId 为本机设备标识（AppConst.androidId），阅读统计按设备独立计算。
 * - 新表从上线版本起开始积累，不回填历史；旧累计表 readRecord 原样保留。
 *
 * 时间字段均为墙上时钟毫秒；date 为设备本地时区的自然日期 "yyyy-MM-dd"。
 */
@Entity(
    tableName = "readRecordDetail",
    primaryKeys = ["deviceId", "bookName", "bookAuthor", "date"],
    indices = [Index(value = ["date"])]
)
data class ReadRecordDetail(
    var deviceId: String = "",
    var bookName: String = "",
    var bookAuthor: String = "",
    var date: String = "",
    /** 该行（设备×书籍×日期）当日累计阅读时长，毫秒 */
    var readTime: Long = 0L,
    /** 当日该段阅读的真实开始时刻（非整段窗口起点） */
    var firstReadTime: Long = 0L,
    /** 当日该段阅读的真实结束时刻 */
    var lastReadTime: Long = 0L,
    /** 写入时 Book.type 快照；漫画判定为 (bookType and BookType.image) != 0 */
    var bookType: Int = 0
)
