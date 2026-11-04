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
    private var savedReadBook: io.legado.app.data.entities.Book? = null
    private var savedReadManga: io.legado.app.data.entities.Book? = null

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 阅读模型是进程级单例：暂存活动书籍并在结束后恢复，避免用例互相污染
        savedReadBook = io.legado.app.model.ReadBook.book
        savedReadManga = io.legado.app.model.ReadManga.book
        io.legado.app.model.ReadBook.book = null
        io.legado.app.model.ReadManga.book = null
        io.legado.app.model.ReadBook.resetReadRecordCache()
        io.legado.app.model.ReadManga.resetReadRecordCache()
    }

    @After
    fun tearDown() {
        io.legado.app.model.ReadBook.book = savedReadBook
        io.legado.app.model.ReadManga.book = savedReadManga
        io.legado.app.model.ReadBook.resetReadRecordCache()
        io.legado.app.model.ReadManga.resetReadRecordCache()
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
        db.readRecordDetailDao.insert(row())
        val got = db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")
        assertEquals(60_000L, got?.readTime)
        assertNull(db.readRecordDetailDao.get("devB", "书", "作者", "2026-11-04"))
    }

    @Test
    fun differentDevicesDoNotOverwriteEachOther() {
        db.readRecordDetailDao.insert(row(deviceId = "devA", readTime = 100_000L))
        db.readRecordDetailDao.insert(row(deviceId = "devB", readTime = 200_000L))
        assertEquals(100_000L, db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")?.readTime)
        assertEquals(200_000L, db.readRecordDetailDao.get("devB", "书", "作者", "2026-11-04")?.readTime)
        // 聚合口径：跨设备求和（各设备独立计算，展示时合并求和）
        assertEquals(
            300_000L,
            db.readRecordDetailDao.sumRange("2026-11-01", "2026-11-05")
        )
    }

    @Test
    fun sameAuthorOnlyNoMergeForDifferentAuthor() {
        db.readRecordDetailDao.insert(row(bookAuthor = "作者甲"))
        db.readRecordDetailDao.insert(row(bookAuthor = "作者乙"))
        assertEquals(
            2,
            db.readRecordDetailDao.getByDateRange("2026-11-01", "2026-11-05").size
        )
        // 按书名删除（记录 Tab 删除语义）：同名跨作者一并清除，且不误删他书
        db.readRecordDetailDao.insert(row(bookName = "其他书"))
        db.readRecordDetailDao.deleteByName("书")
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals("其他书", db.readRecordDetailDao.all[0].bookName)
    }

    @Test
    fun rangeSumAndRankGroupByBook() {
        db.readRecordDetailDao.insert(row(readTime = 100_000L))
        db.readRecordDetailDao.insert(
            row(bookAuthor = "作者乙", readTime = 50_000L, date = "2026-11-05")
        )
        db.readRecordDetailDao.insert(row(date = "2026-10-31", readTime = 999_999L))
        assertEquals(
            150_000L,
            db.readRecordDetailDao.sumRange("2026-11-01", "2026-11-06")
        )
        val rank = db.readRecordDetailDao.rankRange("2026-11-01", "2026-11-06")
        assertEquals(2, rank.size)
        assertEquals("书", rank[0].bookName)
        assertEquals(150_000L, rank[0].readTime)
        assertTrue(rank[0].isManga)
    }

    @Test
    fun updateRowKeepsPrimaryKey() {
        db.readRecordDetailDao.insert(row(readTime = 10_000L))
        val old = db.readRecordDetailDao.get("devA", "书", "作者", "2026-11-04")!!
        old.readTime = 30_000L
        db.readRecordDetailDao.update(old)
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals(30_000L, db.readRecordDetailDao.all[0].readTime)
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
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = "devA"; bookName = "书"; readTime = 1L
            }
        )
        db.readRecordDetailDao.insert(row())
        // 清空两张统计表
        db.runInTransaction {
            db.readRecordDao.clear()
            db.readRecordDetailDao.clear()
        }
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        // 书籍/进度未被误删
        assertEquals(1, db.bookDao.all.size)
        assertTrue(db.bookChapterDao.getChapterCount("https://example.com/book") >= 0)
        assertFalse(db.readRecordDetailDao.all.isNotEmpty())
    }

    @Test
    fun appendWindowAccumulatesExistingIdentity() {
        // 找得到原统计身份：继续累计原记录
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "连载书", bookAuthor = "作者", readTime = 100_000L)
        )
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "连载书", "作者", 8, 1_000_000L, 1_050_000L, db
        )
        val rows = db.readRecordDetailDao.all
        assertEquals(1, rows.size)
        assertEquals(150_000L, rows[0].readTime)
        // 累计表同键 REPLACE 语义：同书名继续累计不翻倍
        assertEquals(1, db.readRecordDao.all.size)
    }

    @Test
    fun appendWindowCreatesNewRecordWhenIdentityNotFound() {
        // 找不到原统计身份：创建新记录（换源后新身份自然开新账）
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "旧名", bookAuthor = "旧作者", readTime = 100_000L)
        )
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "新名", "新作者", 8, 1_000_000L, 1_050_000L, db
        )
        assertEquals(2, db.readRecordDetailDao.all.size)
        // 新身份正常累计
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "新名", "新作者", 8, 2_000_000L, 2_100_000L, db
        )
        val newRows = db.readRecordDetailDao.all.filter {
            it.bookName == "新名" && it.bookAuthor == "新作者"
        }
        assertEquals(150_000L, newRows.sumOf { it.readTime })
    }

    @Test
    fun distinctIdentitiesCoexistAndKeepOldRecords() {
        // 新旧身份分别保留：新记录不覆盖旧身份历史；同名不同作者不合并
        val androidId = io.legado.app.constant.AppConst.androidId
        db.readRecordDetailDao.insert(
            row(deviceId = androidId, bookName = "同名书", bookAuthor = "作者甲", readTime = 100_000L)
        )
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "同名书", "作者乙", 8, 1_000_000L, 1_100_000L, db
        )
        io.legado.app.help.book.ReadRecordDetailHelper.appendWindow(
            "改名书", "作者甲", 8, 2_000_000L, 2_200_000L, db
        )
        assertEquals(3, db.readRecordDetailDao.all.size)
        // 旧记录原样保留，数值不被新记录影响
        assertEquals(
            100_000L,
            db.readRecordDetailDao.get(androidId, "同名书", "作者甲", "2026-11-04")?.readTime
        )
        // 范围查询把各身份记录都计入每日统计
        assertEquals(
            400_000L,
            db.readRecordDetailDao.getByDateRange("2026-11-01", "2026-11-05").sumOf { it.readTime }
        )
        assertEquals(
            3,
            db.readRecordDetailDao.getByDateRange("2026-11-01", "2026-11-05")
                .map { it.bookName to it.bookAuthor }.distinct().size
        )
    }

    @Test
    fun aggregateQueriesWorkUnderVolume() {
        val androidId = io.legado.app.constant.AppConst.androidId
        val rows = ArrayList<ReadRecordDetail>(30 * 50)
        for (day in 1..30) {
            for (book in 1..50) {
                rows.add(
                    row(
                        deviceId = androidId,
                        bookName = "书$book",
                        bookAuthor = "作者",
                        date = String.format(java.util.Locale.ROOT, "2026-10-%02d", day),
                        readTime = (book * 1000).toLong()
                    )
                )
            }
        }
        db.runInTransaction {
            db.readRecordDetailDao.insert(*rows.toTypedArray())
        }
        assertEquals(1500, db.readRecordDetailDao.all.size)
        assertEquals(30, db.readRecordDetailDao.distinctDateCount())
        // sum = 50 本 × 每本每天 book*1000 × 30 天
        var expected = 0L
        for (book in 1..50) expected += (book * 1000L) * 30
        assertEquals(expected, db.readRecordDetailDao.totalSum)
        val rank = db.readRecordDetailDao.rankRange("2026-10-01", "2026-11-01")
        assertEquals(50, rank.size)
        assertEquals("书50", rank[0].bookName)
        assertEquals(50_000L * 30, rank[0].readTime)
    }

    /** 窗口起点对应的本地自然日（待冲正窗口的日期归属验证用） */
    private fun dateOf(epochMs: Long): String {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }

    @Test
    fun doubleWriteTransactionRollsBackAtomically() {
        // 用真实 SQLite 触发器在每日明细插入时制造失败，验证双表同事务整体回滚
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_detail_insert before insert on readRecordDetail " +
                    "begin select raise(abort, 'simulated io failure'); end"
        )
        val threw = runCatching {
            db.runInTransaction {
                db.readRecordDao.insert(
                    io.legado.app.data.entities.ReadRecord().apply {
                        deviceId = ""; bookName = "事务书"; readTime = 1000L
                    }
                )
                db.readRecordDetailDao.insert(row(bookName = "事务书"))
            }
            false
        }.getOrElse { true }
        assertTrue("事务应抛出异常", threw)
        // 累计表写入随明细失败整体回滚
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        support.execSQL("drop trigger fail_detail_insert")
        // 重试成功
        db.runInTransaction {
            db.readRecordDao.insert(
                io.legado.app.data.entities.ReadRecord().apply {
                    deviceId = ""; bookName = "事务书"; readTime = 1000L
                }
            )
            db.readRecordDetailDao.insert(row(bookName = "事务书"))
        }
        assertEquals(1000L, db.readRecordDao.all[0].readTime)
        assertEquals(1, db.readRecordDetailDao.all.size)
    }

    @Test
    fun retryPendingWorkAppliesWindowExactlyOnce() {
        val androidId = io.legado.app.constant.AppConst.androidId
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "待冲正书", "作者", 8, 1_000_000L, 1_200_000L
        )
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        val date = dateOf(1_000_000L)
        // 每日明细补写，时长=窗口长度
        assertEquals(
            200_000L,
            db.readRecordDetailDao.get(androidId, "待冲正书", "作者", date)?.readTime
        )
        // 累计表本机分区同步补齐
        assertEquals(200_000L, db.readRecordDao.get("", "待冲正书")?.readTime)
        // 重复重试不重复累计
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(
            200_000L,
            db.readRecordDetailDao.all.filter { it.bookName == "待冲正书" }.sumOf { it.readTime }
        )
        assertEquals(200_000L, db.readRecordDao.get("", "待冲正书")?.readTime)
    }

    @Test
    fun ownPartitionQueryExcludesForeignDeviceRows() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "分区书"; readTime = 50_000L
            }
        )
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = "otherDev"; bookName = "分区书"; readTime = 100_000L
            }
        )
        // 计时基数只取本机分区；跨设备求和仅用于展示口径
        assertEquals(50_000L, db.readRecordDao.getOwnReadTime("分区书"))
        assertEquals(150_000L, db.readRecordDao.getReadTime("分区书"))
    }

    @Test
    fun deleteByNameMatchesUiDeclaredScope() {
        val book = io.legado.app.data.entities.Book()
        book.name = "同名书"
        book.author = "作者甲"
        book.bookUrl = "https://a.example/x"
        book.origin = "https://a.example"
        db.bookDao.insert(book)
        db.readRecordDetailDao.insert(row(bookName = "同名书", bookAuthor = "作者甲"))
        db.readRecordDetailDao.insert(row(bookName = "同名书", bookAuthor = "作者乙"))
        db.readRecordDetailDao.insert(row(bookName = "其他书"))
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "同名书"; readTime = 1L
            }
        )
        // 与 UI 提示一致：删除书名聚合行会同时移除同名不同作者的统计
        db.readRecordDao.deleteByName("同名书")
        db.readRecordDetailDao.deleteByName("同名书")
        assertEquals(1, db.readRecordDetailDao.all.size)
        assertEquals("其他书", db.readRecordDetailDao.all[0].bookName)
        assertEquals(0, db.readRecordDao.all.size)
        // 书籍本身不受影响
        assertEquals(1, db.bookDao.all.size)
    }

    @Test
    fun clearFailureKeepsPendingWorkAndData() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 50_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 20_000L))
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "清空书", "作者", 8, 3_000_000L, 3_100_000L
        )
        // 触发器让清空事务失败
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_clear before delete on readRecord " +
                    "begin select raise(abort, 'simulated failure'); end"
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = true
        io.legado.app.help.book.ReadRecordDetailHelper.clearAllStatistics(db) { result ->
            ok = result
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse("清空事务应失败", ok)
        support.execSQL("drop trigger fail_clear")
        // 数据库整体回滚：旧数据原样保留
        assertEquals(50_000L, db.readRecordDao.get("", "清空书")?.readTime)
        // 待冲正窗口未被提前丢弃：重试仍然生效（旧数据 + 冲正窗口）
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(60_000L, db.readRecordDao.get("", "清空书")?.readTime)
        assertEquals(30_000L, db.readRecordDetailDao.all.sumOf { it.readTime })
    }

    @Test
    fun clearSuccessDropsPendingAndAllowsNewReading() {
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 50_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 20_000L))
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "清空书", "作者", 8, 3_000_000L, 3_100_000L
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        io.legado.app.help.book.ReadRecordDetailHelper.clearAllStatistics(db) { result ->
            ok = result
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(ok)
        assertEquals(0, db.readRecordDao.all.size)
        assertEquals(0, db.readRecordDetailDao.all.size)
        // 清空前的待冲正窗口不复活
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(0, db.readRecordDetailDao.all.size)
        assertEquals(0, db.readRecordDao.all.size)
        // 清空后新阅读正常记录
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "清空书"; readTime = 5_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "清空书", readTime = 5_000L))
        assertEquals(5_000L, db.readRecordDao.all[0].readTime)
        assertEquals(1, db.readRecordDetailDao.all.size)
    }

    @Test
    fun rollbackUncommittedWindowRestoresExactMemory() {
        io.legado.app.model.ReadBook.book =
            io.legado.app.data.entities.Book(name = "回滚书", author = "作者")
        io.legado.app.model.ReadBook.resetReadRecordCache()
        val memBefore = io.legado.app.model.ReadBook.readTimeForTest
        assertEquals(0L, memBefore)
        // 构造"内存已推进 10 秒但窗口未提交"的状态（测试中抬高内存累计的受控入口）
        assertTrue(io.legado.app.model.ReadBook.addReconciledReadTime("回滚书", 10_000L))
        // 撤销未提交推进：恢复推进前原值，而不是按差值误减
        io.legado.app.model.ReadBook.rollbackUncommittedWindow("回滚书", memBefore, 10_000L)
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
    }

    @Test
    fun rollbackUncommittedWindowIgnoresMismatchedIdentityOrValue() {
        io.legado.app.model.ReadBook.book =
            io.legado.app.data.entities.Book(name = "守卫书", author = "作者")
        io.legado.app.model.ReadBook.resetReadRecordCache()
        assertTrue(io.legado.app.model.ReadBook.addReconciledReadTime("守卫书", 10_000L))
        // 书名不匹配（失败后已切书，内存已被另一本书整体重载）：不得撤销
        io.legado.app.model.ReadBook.rollbackUncommittedWindow("别的书", 0L, 10_000L)
        assertEquals(10_000L, io.legado.app.model.ReadBook.readTimeForTest)
        // 数值不匹配（内存不是"该快照 + 该窗口"）：不得撤销
        io.legado.app.model.ReadBook.rollbackUncommittedWindow("守卫书", 5_000L, 10_000L)
        assertEquals(10_000L, io.legado.app.model.ReadBook.readTimeForTest)
        // 身份与数值都匹配才撤销
        io.legado.app.model.ReadBook.rollbackUncommittedWindow("守卫书", 0L, 10_000L)
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
    }

    @Test
    fun addReconciledReadTimeInjectsOnlyCurrentBook() {
        // 无活动书籍：不补入
        assertFalse(io.legado.app.model.ReadBook.addReconciledReadTime("任何书", 5_000L))
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
        io.legado.app.model.ReadBook.book =
            io.legado.app.data.entities.Book(name = "在读书", author = "作者甲")
        // 非当前活动书籍的窗口不补入
        assertFalse(io.legado.app.model.ReadBook.addReconciledReadTime("其他书", 5_000L))
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
        // 累计表本机行按书名聚合（同名不同作者共享），同名即须补入以防 REPLACE 丢失
        assertTrue(io.legado.app.model.ReadBook.addReconciledReadTime("在读书", 5_000L))
        assertEquals(5_000L, io.legado.app.model.ReadBook.readTimeForTest)
    }

    @Test
    fun mangaMemoryRollbackAndReconcileMirrorNovel() {
        io.legado.app.model.ReadManga.book =
            io.legado.app.data.entities.Book(name = "漫画书", author = "作者", type = 8)
        io.legado.app.model.ReadManga.resetReadRecordCache()
        assertTrue(io.legado.app.model.ReadManga.addReconciledReadTime("漫画书", 10_000L))
        assertEquals(10_000L, io.legado.app.model.ReadManga.readTimeForTest)
        io.legado.app.model.ReadManga.rollbackUncommittedWindow("漫画书", 0L, 10_000L)
        assertEquals(0L, io.legado.app.model.ReadManga.readTimeForTest)
        // 小说与漫画两条链路互不串内存
        assertFalse(io.legado.app.model.ReadBook.addReconciledReadTime("漫画书", 3_000L))
        assertTrue(io.legado.app.model.ReadManga.addReconciledReadTime("漫画书", 3_000L))
        assertEquals(3_000L, io.legado.app.model.ReadManga.readTimeForTest)
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
    }

    @Test
    fun settleFailureThenReconcileCountsWindowOnce() {
        // 场景 A/B/D（真实 SQLite）：基线累计 100_000；w1=10_000 结算失败
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书A"; readTime = 100_000L
            }
        )
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "书A", "作者A", 0, 1_000_000L, 1_010_000L
        )
        // 失败未提交：数据库保持 100_000
        assertEquals(100_000L, db.readRecordDao.get("", "书A")?.readTime)
        // w2=20_000 正常结算（等价 settleWindow 已提交路径：REPLACE + appendWindow）
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书A"; readTime = 120_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "书A", bookAuthor = "作者A", readTime = 20_000L))
        // 冲正 w1：累计表 130_000；当前活动书籍内存同步补入该窗口
        io.legado.app.model.ReadBook.book =
            io.legado.app.data.entities.Book(name = "书A", author = "作者A")
        io.legado.app.model.ReadBook.resetReadRecordCache()
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(130_000L, db.readRecordDao.get("", "书A")?.readTime)
        assertEquals(10_000L, io.legado.app.model.ReadBook.readTimeForTest)
        // 每日明细合计 30_000（失败窗口 10_000 + 正常窗口 20_000），不是 40_000
        assertEquals(
            30_000L,
            db.readRecordDetailDao.all.filter { it.bookName == "书A" }.sumOf { it.readTime }
        )
        // 场景 B：冲正后再结算 5_000——写库以"含冲正补差的内存"为基数，已冲正时长不丢
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书A"; readTime = 135_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "书A", bookAuthor = "作者A", readTime = 5_000L))
        assertEquals(135_000L, db.readRecordDao.get("", "书A")?.readTime)
        assertEquals(
            35_000L,
            db.readRecordDetailDao.all.filter { it.bookName == "书A" }.sumOf { it.readTime }
        )
        // 场景 D：w4=8_000 再次失败入队；触发器让第一次冲正事务失败
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "书A", "作者A", 0, 2_000_000L, 2_008_000L
        )
        val support = db.openHelper.writableDatabase
        support.execSQL(
            "create trigger fail_reconcile before update on readRecord " +
                    "begin select raise(abort, 'simulated failure'); end"
        )
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        support.execSQL("drop trigger fail_reconcile")
        // 第一次冲正失败：两表不动、内存不补、队列保留
        assertEquals(135_000L, db.readRecordDao.get("", "书A")?.readTime)
        assertEquals(10_000L, io.legado.app.model.ReadBook.readTimeForTest)
        assertEquals(
            35_000L,
            db.readRecordDetailDao.all.filter { it.bookName == "书A" }.sumOf { it.readTime }
        )
        // 第二次冲正成功：w4 只入账一次（累计 143_000、明细 43_000、内存同步）
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(143_000L, db.readRecordDao.get("", "书A")?.readTime)
        assertEquals(18_000L, io.legado.app.model.ReadBook.readTimeForTest)
        assertEquals(
            43_000L,
            db.readRecordDetailDao.all.filter { it.bookName == "书A" }.sumOf { it.readTime }
        )
        // 队列已清空：连续重试不再变化
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        assertEquals(143_000L, db.readRecordDao.get("", "书A")?.readTime)
    }

    @Test
    fun reconcileAfterBookSwitchOnlyTouchesSwitchedOutBookRow() {
        // 场景 C：书A 结算失败入队后切到书B；书B 正常结算并触发冲正
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书A"; readTime = 50_000L
            }
        )
        io.legado.app.help.book.ReadRecordDetailHelper.enqueuePendingStat(
            "书A", "作者A", 0, 3_000_000L, 3_010_000L
        )
        // 切到书B（等价 resetData：内存整体重载为书B），书B 正常结算 7_000
        io.legado.app.model.ReadBook.book =
            io.legado.app.data.entities.Book(name = "书B", author = "作者B")
        io.legado.app.model.ReadBook.resetReadRecordCache()
        db.readRecordDao.insert(
            io.legado.app.data.entities.ReadRecord().apply {
                deviceId = ""; bookName = "书B"; readTime = 7_000L
            }
        )
        db.readRecordDetailDao.insert(row(bookName = "书B", bookAuthor = "作者B", readTime = 7_000L))
        io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
        // 书A 累计正确补入 10_000；书B 只有自己的 7_000
        assertEquals(60_000L, db.readRecordDao.get("", "书A")?.readTime)
        assertEquals(7_000L, db.readRecordDao.get("", "书B")?.readTime)
        // 书A 的冲正不得修改书B 的内存累计
        assertEquals(0L, io.legado.app.model.ReadBook.readTimeForTest)
        // 补差 API 只认当前活动书籍
        assertFalse(io.legado.app.model.ReadBook.addReconciledReadTime("书A", 1_000L))
        assertTrue(io.legado.app.model.ReadBook.addReconciledReadTime("书B", 1_000L))
        assertEquals(1_000L, io.legado.app.model.ReadBook.readTimeForTest)
    }

    /** 直接驱动生产 settleWindow + Room，不再只手写“等价”结算。 */
    @Test
    fun actualNovelAndMangaSettlementPersistsSixtyAndNinetySeconds() {
        val start = 1_794_000_000_000L
        for (manga in listOf(false, true)) {
            val identity = io.legado.app.help.book.ReadingTimeIdentity(
                if (manga) "计时漫画" else "计时小说", "作者", if (manga) 8 else 0
            )
            val timer = modelTimer(manga)
            timer.bind(identity, start)
            timer.resume(start)
            timer.checkpoint(start + 1_000L)
            timer.pause(start + 60_000L)
            assertEquals(60_000L, db.readRecordDao.getOwnReadTime(identity.name))
            assertEquals(60_000L, db.readRecordDetailDao.all.filter { it.bookName == identity.name }.sumOf { it.readTime })
            timer.resume(start + 360_000L)
            timer.checkpoint(start + 375_000L)
            timer.pause(start + 390_000L)
            assertEquals(90_000L, db.readRecordDao.getOwnReadTime(identity.name))
            assertEquals(90_000L, db.readRecordDetailDao.all.filter { it.bookName == identity.name }.sumOf { it.readTime })
        }
    }

    @Test
    fun actualSettlementFailureRetryAndSwitchDoNotDoubleCount() {
        val start = 1_794_000_000_000L
        for (manga in listOf(false, true)) {
            val identity = io.legado.app.help.book.ReadingTimeIdentity(
                if (manga) "失败漫画" else "失败小说", "作者", if (manga) 8 else 0
            )
            val timer = modelTimer(manga)
            timer.bind(identity, start)
            timer.resume(start)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_timing BEFORE INSERT ON readRecordDetail BEGIN SELECT RAISE(ABORT, 'test failure'); END"
            )
            timer.checkpoint(start + 15_000L)
            assertNull(db.readRecordDao.getOwnReadTime(identity.name))
            assertEquals(0L, if (manga) io.legado.app.model.ReadManga.readTimeForTest else io.legado.app.model.ReadBook.readTimeForTest)
            io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
            assertNull(db.readRecordDao.getOwnReadTime(identity.name))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_timing")
            timer.checkpoint(start + 30_000L)
            assertEquals(30_000L, db.readRecordDao.getOwnReadTime(identity.name))
            assertEquals(30_000L, if (manga) io.legado.app.model.ReadManga.readTimeForTest else io.legado.app.model.ReadBook.readTimeForTest)
            io.legado.app.help.book.ReadRecordDetailHelper.retryPendingWork(db)
            timer.bind(identity.copy(name = identity.name + "B"), start + 60_000L)
            timer.pause(start + 150_000L)
            assertEquals(60_000L, db.readRecordDao.getOwnReadTime(identity.name))
            assertEquals(90_000L, db.readRecordDao.getOwnReadTime(identity.name + "B"))
            assertEquals(60_000L, db.readRecordDetailDao.all.filter { it.bookName == identity.name }.sumOf { it.readTime })
        }
    }

    private fun modelTimer(manga: Boolean) =
        io.legado.app.help.book.ReadingSessionTimer<io.legado.app.help.book.ReadingTimeIdentity>(
            onIdentityChanged = { identity ->
                val book = io.legado.app.data.entities.Book(name = identity.name, author = identity.author, type = identity.type)
                if (manga) {
                    io.legado.app.model.ReadManga.book = book
                    io.legado.app.model.ReadManga.loadReadRecord(identity, db)
                } else {
                    io.legado.app.model.ReadBook.book = book
                    io.legado.app.model.ReadBook.loadReadRecord(identity, db)
                }
            },
            onWindow = { identity, start, end ->
                if (manga) io.legado.app.model.ReadManga.settleWindow(identity, start, end, db)
                else io.legado.app.model.ReadBook.settleWindow(identity, start, end, db)
            }
        )

}
