package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.ReadRecordDetail
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 每日统计表真实 SQLite/Room 测试（需在真机或模拟器执行：
 * `gradlew connectedAppReleaseAndroidTest` 或 Android Studio 运行）。
 * 覆盖：PK 唯一与更新、设备隔离、范围聚合、按书删除、两表清空互不影响书架/进度表。
 */
@RunWith(AndroidJUnit4::class)
class ReadRecordDetailDbTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        deviceId: String = "devA",
        bookName: String = "书",
        bookAuthor: String = "作者",
        date: String = "2026-11-04",
        readTime: Long = 60_000L
    ) = ReadRecordDetail(
        deviceId = deviceId, bookName = bookName, bookAuthor = bookAuthor,
        date = date, readTime = readTime, firstReadTime = 1_000L,
        lastReadTime = 1_000L + readTime, bookType = 8
    )

    @Test
    fun insertAndGetByPrimaryKey() {
        db.readRecordDetailDao().insert(row())
        val got = db.readRecordDetailDao().get("devA", "书", "作者", "2026-11-04")
        assertEquals(60_000L, got?.readTime)
        assertNull(db.readRecordDetailDao().get("devB", "书", "作者", "2026-11-04"))
    }

    @Test
    fun differentDevicesDoNotOverwriteEachOther() {
        db.readRecordDetailDao().insert(row(deviceId = "devA", readTime = 100_000L))
        db.readRecordDetailDao().insert(row(deviceId = "devB", readTime = 200_000L))
        assertEquals(100_000L, db.readRecordDetailDao().get("devA", "书", "作者", "2026-11-04")?.readTime)
        assertEquals(200_000L, db.readRecordDetailDao().get("devB", "书", "作者", "2026-11-04")?.readTime)
        // 聚合口径：跨设备求和（各设备独立计算，展示时合并求和）
        assertEquals(
            300_000L,
            db.readRecordDetailDao().sumRange("2026-11-01", "2026-11-05")
        )
    }

    @Test
    fun sameAuthorOnlyNoMergeForDifferentAuthor() {
        db.readRecordDetailDao().insert(row(bookAuthor = "作者甲"))
        db.readRecordDetailDao().insert(row(bookAuthor = "作者乙"))
        assertEquals(
            2,
            db.readRecordDetailDao().getByDateRange("2026-11-01", "2026-11-05").size
        )
        // 按书名删除（记录 Tab 删除语义）：同名跨作者一并清除，且不误删他书
        db.readRecordDetailDao().insert(row(bookName = "其他书"))
        db.readRecordDetailDao().deleteByName("书")
        assertEquals(1, db.readRecordDetailDao().all.size)
        assertEquals("其他书", db.readRecordDetailDao().all[0].bookName)
    }

    @Test
    fun rangeSumAndRankGroupByBook() {
        db.readRecordDetailDao().insert(row(readTime = 100_000L))
        db.readRecordDetailDao().insert(
            row(bookAuthor = "作者乙", readTime = 50_000L, date = "2026-11-05")
        )
        db.readRecordDetailDao().insert(row(date = "2026-10-31", readTime = 999_999L))
        assertEquals(
            150_000L,
            db.readRecordDetailDao().sumRange("2026-11-01", "2026-11-06")
        )
        val rank = db.readRecordDetailDao().rankRange("2026-11-01", "2026-11-06")
        assertEquals(2, rank.size)
        assertEquals("书", rank[0].bookName)
        assertEquals(150_000L, rank[0].readTime)
        assertTrue(rank[0].isManga)
    }

    @Test
    fun updateRowKeepsPrimaryKey() {
        db.readRecordDetailDao().insert(row(readTime = 10_000L))
        val old = db.readRecordDetailDao().get("devA", "书", "作者", "2026-11-04")!!
        old.readTime = 30_000L
        db.readRecordDetailDao().update(old)
        assertEquals(1, db.readRecordDetailDao().all.size)
        assertEquals(30_000L, db.readRecordDetailDao().all[0].readTime)
    }

    @Test
    fun clearDetailDoesNotTouchBooksOrProgress() {
        // 建立书籍/进度/书源基线数据
        val book = io.legado.app.data.entities.Book()
        book.name = "书"
        book.author = "作者"
        book.bookUrl = "https://example.com/book"
        book.origin = "https://example.com"
        db.bookDao.insert(book)
        db.readRecordDao().insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = "devA"; bookName = "书"; readTime = 1L
            }
        )
        db.readRecordDetailDao().insert(row())
        // 清空两张统计表
        db.runInTransaction {
            db.readRecordDao().clear()
            db.readRecordDetailDao().clear()
        }
        assertEquals(0, db.readRecordDao().all.size)
        assertEquals(0, db.readRecordDetailDao().all.size)
        // 书籍/进度未被误删
        assertEquals(1, db.bookDao.all.size)
        assertTrue(db.bookChapterDao.getChapterCount("https://example.com/book") >= 0)
        assertFalse(db.readRecordDetailDao().all.isNotEmpty())
    }

}
